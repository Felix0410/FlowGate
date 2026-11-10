# FlowGate V1.2｜正式设计决定与V11兼容裁决（ADR总表）

> 2026-11-10 · **设计冻结候选，尚未实现**。此文档在逻辑冲突时优先于2026-11-10早先的总体提案；后续Codex按这里的边界实现，不应临场再选别的权益/回滚架构。项目负责人可在派工前审阅调整；变更必须追加ADR，不能悄悄改变约束。

## ADR-01：入口和权限

- Portal `/portal/`和Portal业务接口`/portal/api/**`是**普通用户**入口，独立`PORTAL_USER`主体、独立Redis Token摘要命名空间、自己的Filter/Interceptor；旧`/admin/**`、`/internal/**`、Gateway旧CALLER/APP_CALLER全都保持用途不互认，`/console/`不成为Portal的隐藏菜单。
- 复用现有`fg_principal`中的BCrypt字段与`fg_developer`、`fg_application`。Portal个人资料独立表保存`login_name_normalized`唯一值，`fg_principal.username`为非用户可编辑的内部`portal-<UUID>`（避免与ADMIN全局用户名唯一键冲突），`fg_developer_member(developer_id,principal_id,role)`承载真实OWNER归属。注册必须同事务创建三方，不能先成功写Redis再发现数据库事务回滚。
- 所有Portal写操作先从服务器会话取得principal，再查工作区/APP所有权；客户端即使填写developerId/applicationId也只当被核验的**对象选择**，不是授权事实。跨租户详情一律404（含order、quota、Key摘要）；不泄露对象是否存在。
- Portal前端单独`static/portal/`原生ESM，Console继续`static/console/`七域，用户端不能复用管理端已认证的fetch客户端。无默认账号、无真实支付。
- Bearer Portal Token只驻内存，刷新需重登；现有App调用Token TTL **3600秒**、只展示一次。用户端须明确“短期API调用凭证”，不能误标永久API Key；要做长期Key需未来独立安全ADR，不私改B代次/TTL。登出删除Portal会话，账号禁用后已发凭证由服务端资格持续复验。

## ADR-02：新增API商店不等于修改已发布路由

- `fg_api`、`fg_service`、`fg_route`和发布快照仍是业务API唯一真实代理来源。市场`listing(api_id UNIQUE,category,description,status)`及不可变`sku(listing,version,quota,priceCents,periodPolicy)`只有商品意义。
- 产品listing只有在当前实际发布存在私有且受V12计量规则保护的API，完整接流节点具备`USAGE_QUOTA_V12`能力，才能显示“可领取/可购买”；上下架/改价不能重新发布API也不能追溯修改历史SKU快照。
- API详情、分类、公开文档默认只读MySQL；V12-D有真实测量后再引Cache Aside、空值缓存和Redis热榜。缓存失效不得导致订单采用过期价。

## ADR-03：V11与V12两套配额并存，且模式由可信版本固化

- 原V11表`fg_subscription`、`fg_quota_balance`、`fg_quota_ledger`、`fg_outbox_event`、`fg_consumed_event`、`fg_usage_daily`不改写历史行。V11仍是APP/UTC月份唯一额度池，冻住`subscription_id`、`planId`及套餐单位，旧QuotaService完全维持。
- V12新增`fg_api_subscription`、`fg_api_quota_balance`、`fg_api_quota_ledger`，以**APP+API+UTC月**唯一，单次授权不得从老池兜底。新增且显式的V12 policy type `USAGE_QUOTA_V12`、执行标识`ENFORCED_QUOTA_V12`、可信节点Feature`USAGE_QUOTA_V12`；旧`USAGE_QUOTA`仍保持`ENFORCED_QUOTA_V1`。
- 更新`QuotaPolicy.freeze()`/Contract为显式`mode=NONE|V11_APP|V12_APP_API`，解析旧schemaVersion=1快照时**默认保留V11/none老语义**；不得因升级重新序列化或改变旧checksum，不能在同一实际route同时生效V11和V12，不得使旧Gateway或不支持V12的节点接流。旧Gateway不能应用新规则时Fail Closed且不变成普通私有Caller。
- `QuotaAdmissionGlobalFilter`按可信metadata执行有界WebClient非阻塞到`/internal/quota/charge`或`/internal/quota/charge-v12`，共用原inflight、retry sameChargeId、timeout、安全指标，旧Caller不变。V12在Control凭可信principal→APP/Developer/Scope→路由策略→指定API月权益→锁余额→去重Ledger→扣减/Outbox同事务。Grant发生即消耗，下游失败不退款。
- V12不使用旧C的聚合表替代新模式：新增独立`fg_api_usage_outbox`、`fg_api_consumed_event`、`fg_api_usage_daily`及`flowgate.usage.v12`独立消费者组；消费者校验V12 Ledger/同事件再同事务去重与日报UPSERT。**不能把V12事件直接送入C现有严格八字段的`UsageGrantedEvent`或混入`fg_usage_daily`造成不同来源难对账。** Portal用量页面清楚区分**MySQL权威授权流水**与**Kafka异步日报可能滞后**。未来统一报表用只读UNION/来源标识，不在热路径合并。
- 若出于实现成本后续要合并旧C异步表，必须另起ADR并补齐`metering_mode`迁移、复合唯一键、全部日聚合与孤儿审计的真实回归；当前禁止隐式合流。

## ADR-04：BASE权益在付款前预占槽位，避免双付单开通失败

**修正早期提案的关键业务漏洞：若只在支付后用`UNIQUE(APP,API,month)`拒绝重复BASE，则用户可能付了两张订单，却只有一份权益能够建立。**

- 新增`fg_api_entitlement_slot`主键或唯一`(application_id,api_id,period_yyyymm)`。固定一行状态`AVAILABLE|HELD|GRANTED`、`holder_type=ORDER|PROMO`、`holder_id`、`version`。免费领取直接在一笔事务从AVAILABLE→GRANTED（或插入GRANTED）；普通BASE订单**创建事务内先将槽置HELD**且绑定当前order，返回待支付；抢购在写MySQL持久intent/库存reserved/业务Outbox**同一事务**将槽置HELD且绑定intent。
- **唯一锁顺序：应用行FOR UPDATE → 权益槽行FOR UPDATE（必要时先INSERT IGNORE再当前读）→ 订单或intent行 → 活动库存行 → 订阅/余额 → Outbox与审计。** 所有竞争路径必须遵循与现有V11一致的应用锁并避免锁序反转。若单独为库存锁优化吞吐，需做明确并发死锁测试及独立ADR，不允许开发中擅自变更锁序。
- HELD只可能由其持有者按`(holder_type,holder_id,version)`CAS转为GRANTED或AVAILABLE；**付款成功后不得因为短暂异步故障释放槽**，保持HELD直到成功开通或受控冲正；订单被超时关闭/用户取消时才释放到AVAILABLE。历史已GRANTED不可再转AVAILABLE，退款不能清旧Ledger。
- 订单创建、活动受理、免费领取都要在**持应用锁后**校验已有GRANTED与HELD，再决定是否受理；相同幂等请求返回原状态，不同请求的冲突返回409，不能把已付买不到的冲突推给权益Worker。数据库最后用槽唯一和Grant来源唯一兜底。

## ADR-05：首版商品、权益和付费范围

- **SKU单品**：一个API，一段UTC自然月，BASE单位固定；不支持“一包多个API”或多个应用共用一个已购买权益池。免费权益：`UNIQUE(portalUserId,apiId)`，**同一用户对同一个API终身只能免费领取一次**（可以放在自己任意APP，但不能换APP再领）；注册后就能领，首版只支持当月领取且当月有效。
- 付费SKU：在允许购买的APP/API/月中预留BASE槽后才创建PENDING_PAYMENT订单；下月可提前订，但未来单月最早下个UTC月且未越过既有V11兼容边界；**不自动续费、不允许同月升级/重新购买第二个BASE**。套餐下架不影响已确认的历史权益；订单创建后快照冻结价格/单位/币种/时期。
- 单月临近结束的订单要有**付款截止时刻**，不允许跨UTC月误用旧BASE：配置有界出售截止（建议距当月结束≥15分钟），订单超过本月权益到期不得支付或异步改为下月。已付但异步超时到权益过期，进入`FULFILLMENT_REVIEW`/受控模拟退款，不能假开通。
- `TOPUP`、任意同月升级、按使用量现金结算、预付余额、跨月滚存、完整已使用退款均**延期**。首版只做免费BASE（A）、普通BASE订单（B）、特价BASE活动（C）。优惠券不影响C通过门槛。

## ADR-06：订单、模拟结算、异步权益、消息

- 普通订单`PENDING_PAYMENT → PAID_PENDING_FULFILLMENT → FULFILLED`；过期/取消只可从PENDING_PAYMENT出发；支付/关闭在相同MySQL订单行CAS；支付事务写`fg_payment_record`唯一收据及`fg_business_outbox PAYMENT_CONFIRMED`，不在事务里同步调用Kafka。
- 业务Kafka使用**同一Broker新Topic/Group和独立BusinessOutbox/ConsumerDedup**，旧C用量Kafka链完全保持。消费者事务内确认持有槽、引用原订单、唯一Grant、创建月余额、订单FULFILLED，再提交offset；失败重试/永久毒消息/受控SENT补投与C同等级别，不能声称消息恰好一次。
- 付费权益开通失败保持PAID_PENDING_FULFILLMENT或显式RETRY，不释放HELD槽、不会假显示“已开通”；后续对账与恢复必需。

## ADR-07：抢购只对被Redis筛出的候选进行MySQL持久受理

- Redis Lua的活动时间/版本/库存/用户资格+claimId临时预扣都是**可丢失的候选**；只有MySQL同一事务里`fg_api_entitlement_slot HELD`、`fg_promo_stock.reserved+1`（条件`reserved+sold<total`）、唯一`fg_promo_purchase_intent`、`fg_business_outbox`一起提交后，才能返回202且附真实intentId。
- Kafka订单Worker创建PENDING_PAYMENT订单且绑定既有intent，不二次预占槽或库存；模拟支付成功同事务将`reserved--,sold++`并生成付款收据/Outbox，订单过期同事务释放reserved及HELD。各路径按持有者CAS，不可晚到重建订单或二次退库存。
- Redis故障/失忆后活动FAIL-CLOSED进入REBUILDING；基于MySQL权威`total-reserved-sold`和仍有效Redis候选租约重新构建带epoch的新视图，禁止旧Lua/补偿覆盖新epoch。**允许有界少卖，必须不超卖**，恢复/重建需有测试。
- C阶段会重用B同一`fg_business_outbox`和模拟支付状态机，不另造“秒杀订单”。

## ADR-08：验收、Git与技术栈

- 旧V11-D的10万级SQL和四组公平压测仍未执行；E6负责人真人1280/375验收未签，不把E自动Chromium算真人完成。V12-A从隔离V11-E基线开工；新的DDD分包仅留在Control（Portal/Market/Entitlement/Commerce），不增新微服务。
- Java17/SpringBoot4/Gateway WebFlux非阻塞/MySQL8.4/Redis7 Lua/Kafka4.1.1/原生ESM；新增依赖必须有业务必要性。除增量SQL外不改旧表/历史Ledger；不引入Seata/Nacos/ELK/ShardingSphere/Redisson整套/新前端构建工具。
- 旧PR #1～#13不合并、不改base；所有测试新隔离Docker项目/随机端口/私有凭证/不碰用户现有.env/IDE/持久卷。每段真实Java/Node/Python/HTTP/浏览器和安全测试证据，失败留痕，未运行写未运行；不可提前称V12已上线。
