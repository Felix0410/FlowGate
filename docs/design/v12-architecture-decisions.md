# FlowGate V1.2｜正式设计决定与V11兼容裁决（ADR总表）

> 2026-11-10 · **总体架构已定稿。V12-A已编码并有定向证据但A7三项P1待修，合并入V12-B0；V12-B订单/付费/退款仍是设计，未编码。**此文档在逻辑冲突时优先于2026-11-10早先的总体提案；后续Codex按这里的边界实现，不应临场再选别的权益/回滚架构。项目负责人可在派工前审阅调整；变更必须追加ADR，不能悄悄改变约束。

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
- V12不使用旧C的聚合表替代新模式：新增独立`fg_api_usage_outbox`、`fg_api_consumed_event`、`fg_api_usage_daily`及RabbitMQ `flowgate.usage.v12`独立交换机/quorum队列；消费者校验V12 Ledger/同事件再同事务去重与日报UPSERT。**不能把V12事件直接送入C现有严格八字段的`UsageGrantedEvent`或混入`fg_usage_daily`造成不同来源难对账。** Portal用量页面清楚区分**MySQL权威授权流水**与**RabbitMQ异步日报可能滞后**。未来统一报表用只读UNION/来源标识，不在热路径合并。
- 若出于实现成本后续要合并旧C异步表，必须另起ADR并补齐`metering_mode`迁移、复合唯一键、全部日聚合与孤儿审计的真实回归；当前禁止隐式合流。

## ADR-04：BASE权益在付款前预占槽位，避免双付单开通失败

**修正早期提案的关键业务漏洞：若只在支付后用`UNIQUE(APP,API,month)`拒绝重复BASE，则用户可能付了两张订单，却只有一份权益能够建立。**

- 新增`fg_api_entitlement_slot`主键或唯一`(application_id,api_id,period_yyyymm)`。固定一行状态`AVAILABLE|HELD|GRANTED`、`holder_type=FREE_GRANT|ORDER|PROMO`、`holder_id`、`version`。免费领取直接在一笔事务从AVAILABLE→GRANTED（或插入GRANTED）；普通BASE订单**创建事务内先将槽置HELD**且绑定当前order，返回待支付；抢购在写MySQL持久intent/库存reserved/业务Outbox**同一事务**将槽置HELD且绑定intent。
- **唯一锁顺序：应用行FOR UPDATE → 权益槽行FOR UPDATE（必要时先INSERT IGNORE再当前读）→ 订单或intent行 → 活动库存行 → 订阅/余额 → Outbox与审计。** 所有竞争路径必须遵循与现有V11一致的应用锁并避免锁序反转。若单独为库存锁优化吞吐，需做明确并发死锁测试及独立ADR，不允许开发中擅自变更锁序。
- HELD只可能由其持有者按`(holder_type,holder_id,version)`CAS转为GRANTED或AVAILABLE；**付款成功后不得因为短暂异步故障释放槽**，保持HELD直到成功开通或受控冲正；订单被超时关闭/用户取消时才释放到AVAILABLE。历史已GRANTED不可再转AVAILABLE，退款不能清旧Ledger。
- 订单创建、活动受理、免费领取都要在**持应用锁后**校验已有GRANTED与HELD，再决定是否受理；相同幂等请求返回原状态，不同请求的冲突返回409，不能把已付买不到的冲突推给权益Worker。数据库最后用槽唯一和Grant来源唯一兜底。

## ADR-05：首版商品、权益和付费范围

- **SKU单品**：一个API，一段UTC自然月，BASE单位固定；不支持“一包多个API”或多个应用共用一个已购买权益池。免费权益：`UNIQUE(portalUserId,apiId)`，**同一用户对同一个API终身只能免费领取一次**（可以放在自己任意APP，但不能换APP再领）；注册后就能领，首版只支持当月领取且当月有效。
- 付费SKU：在允许购买的APP/API/月中预留BASE槽后才创建PENDING_PAYMENT订单；下月可提前订，但未来单月最早下个UTC月且未越过既有V11兼容边界；**不自动续费、不允许同月升级/重新购买第二个BASE**。套餐下架不影响已确认的历史权益；订单创建后快照冻结价格/单位/币种/时期。
- 单月临近结束的订单要有**付款截止时刻**，不允许跨UTC月误用旧BASE：配置有界出售截止（建议距当月结束≥15分钟），订单超过本月权益到期不得支付或异步改为下月。已付但异步超时到权益过期，进入`FULFILLMENT_REVIEW`/受控模拟退款，不能假开通。
- `TOPUP`、任意同月升级、按使用量现金结算、预付余额、跨月滚存、完整已使用退款均**延期**。首版只做免费BASE（A）、普通BASE订单（B）、特价BASE活动（C）。优惠券不影响C通过门槛。

## ADR-06：订单、模拟结算、异步权益、消息

- 普通订单`PENDING_PAYMENT → PAID_PENDING_FULFILLMENT → FULFILLED`；过期/取消只可从PENDING_PAYMENT出发；支付/关闭在相同MySQL订单行CAS；支付事务写`fg_payment_record`唯一收据及`fg_business_outbox PAYMENT_CONFIRMED`，不在MySQL事务里同步调用RabbitMQ。
- V12-B/C业务消息使用独立RabbitMQ Commerce DirectExchange+quorum主/重试/Poison队列、独立BusinessOutbox/ConsumerDedup，**旧C用量Kafka链完全保留**。消费者事务内确认持有槽、引用原订单、唯一Grant、创建月余额、订单FULFILLED，COMMIT后才basicAck；发布方有publisher confirm+mandatory return，失败重试/永久毒消息/受控SENT补投，不宣称跨系统恰好一次。
- 付费权益开通失败保持PAID_PENDING_FULFILLMENT或显式RETRY，不释放HELD槽、不会假显示“已开通”；后续对账与恢复必需。

## ADR-07：抢购只对被Redis筛出的候选进行MySQL持久受理

- Redis Lua的活动时间/版本/库存/用户资格+claimId临时预扣都是**可丢失的候选**；只有MySQL同一事务里`fg_api_entitlement_slot HELD`、`fg_promo_stock.reserved+1`（条件`reserved+sold<total`）、唯一`fg_promo_purchase_intent`、`fg_business_outbox`一起提交后，才能返回202且附真实intentId。
- RabbitMQ订单Worker创建PENDING_PAYMENT订单且绑定既有intent，不二次预占槽或库存；模拟支付成功同事务将`reserved--,sold++`并生成付款收据/Outbox，订单过期同事务释放reserved及HELD。另设**IntentTimeoutCloser**：持久ACCEPTED若超过订单创建截止仍未成单，同事务将Intent置EXPIRED、释放reserved和其HELD槽并写STOCK_RELEASED；晚到Worker不允许复活。各路径按持有者CAS，不可晚到重建订单或二次退库存。
- Redis故障/失忆后活动FAIL-CLOSED进入REBUILDING；基于MySQL权威`total-reserved-sold`和仍有效Redis候选租约重新构建带epoch的新视图，禁止旧Lua/补偿覆盖新epoch。**允许有界少卖，必须不超卖**，恢复/重建需有测试。
- C阶段会重用B同一`fg_business_outbox`和模拟支付状态机，不另造“秒杀订单”。202回执丢失时提供`GET /portal/api/promotions/{id}/my-claim`按本人身份查持久Intent；只有Redis临时claim则答“尚未持久受理”。B/C详细表字典、受理超时、状态机和真实测试见[交易抢购蓝图](v12-commerce-order-promotion-blueprint.md)。

## ADR-08：验收、Git与技术栈

- 旧V11-D的10万级SQL和四组公平压测仍未执行；E6负责人真人1280/375验收未签，不把E自动Chromium算真人完成。V12-A从隔离V11-E基线开工；新的DDD分包仅留在Control（Portal/Market/Entitlement/Commerce），不增新微服务。
- Java17/SpringBoot4/Gateway WebFlux非阻塞/MySQL8.4/Redis7 Lua/RabbitMQ(V12)/Kafka4.1.1(V11旧链)/原生ESM；新增依赖必须有业务必要性。除增量SQL外不改旧表/历史Ledger；不引入Seata/Nacos/ELK/ShardingSphere/Redisson整套/新前端构建工具。
- 旧PR #1～#13不合并、不改base；所有测试新隔离Docker项目/随机端口/私有凭证/不碰用户现有.env/IDE/持久卷。每段真实Java/Node/Python/HTTP/浏览器和安全测试证据，失败留痕，未运行写未运行；不可提前称V12已上线。

## ADR-13：V12消息链改用RabbitMQ（2026-11-10负责人明确授权）

负责人在实施过程中明确选择“V12改用RabbitMQ，保留V11 Kafka”。此决定覆盖本文及A合同中V12新Topic/Group要求；V11-C的Kafka、原表、原事件格式与历史证据保持不变。

- V12使用Spring Boot官方AMQP，独立持久Direct交换机 `flowgate.usage.v12`、quorum业务队列、5秒TTL重试队列与毒消息隔离队列。quorum死信采用at-least-once和reject-publish。
- MySQL仍是权威：新余额、Ledger、Outbox同事务；RabbitMQ不会参与同步授权扣量。Outbox租约到期可重领，持久消息收到publisher confirm且未发生mandatory return才标SENT；传输不确定保留原eventId重试。
- 消费端先校验本地Ledger/Outbox，同事务去重和日聚合，COMMIT后手动ACK。临时数据库故障经持久重试队列退避，重试副本确认前不ACK原消息；永久格式/权威冲突进入持久隔离队列。SENT未消费仍须300秒等待期、ADMIN审计/CAS补投。
- `FLOWGATE_V12_ASYNC_ENABLED`默认false；关闭不创建V12交换机/队列/监听器，Outbox保留NEW。V12 Rabbit连接配置仅Control持有。全新隔离环境叠加 `compose.v12-a.yml`，不能迁移/重启用户已有Kafka或RabbitMQ。
- 用户同时要求功能优先、避免过度测试。本轮只做编译、已有快速单测、定向前端与隔离功能主链路，100并发/完整历史故障矩阵/负责人手测明确列为未执行，不以局部验证冒充正式验收。


### ADR-13实施后的统一后续合同（2026-11-10补充）

**V12-B普通订单与V12-C限时抢购也一律用RabbitMQ**，不是只把V12-A用量换成Rabbit后在B/C再新建Kafka业务Topic。业务消息使用同一Rabbit Broker上**独立于usage读模型的commerce exchange/quorum主/TTL retry/poison队列及独立BusinessOutbox**；消费端COMMIT后basicAck、confirm且无mandatory return才SENT、持久事件源+业务唯一键抗重复，实际方案见[消息迁移设计补充](v12-rabbitmq-adaptation.md)。本ADR-13优先覆盖旧ADR-03/ADR-06/ADR-07及交易蓝图中提到“V12新Kafka Topic/ConsumerGroup/offset ACK”的旧文本。**V11-C已有Kafka仍然运行**，没有获准做全局消息迁移或删除旧队列；不得把“V12 RabbitMQ”误写成“全仓库无Kafka”。
