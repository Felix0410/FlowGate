# FlowGate V12-C｜高并发API套餐、限时抢购、Redis缓存与可靠异步履约：完整架构设计

> **设计完成不等于实施。** 2026-11-23根据真实远端`feat/v12-m-rabbitmq-only@d6c1f33371690071c4bb7199dc9e7db5598d8079`只读审计编制。此功能已去除当前Kafka运行依赖；PR#19 M8独立复审/负责人手测仍待确认，C0首先核对。新设计分支`docs/v12-c-high-concurrency-cache`直接基于最新M功能代码，不改PR#18/#19/head/base或main。本次仅设计，无C Java/SQL/Redis Lua/测试运行。
>
> **业务目标**：Portal用户看到缓存加速的高频API套餐与真实限时活动；对某个已发布API的付费BASE套餐（例：9.9元/100份）发起抢购，Redis Lua快速挡住绝大多数无效并发请求，MySQL保证绝不超卖/不双占BASE权益/不重复下单，RabbitMQ异步创建待支付订单，再沿用V12-B模拟付款与权益可靠开通。状态与退货限制真实可解释，Console能上下架、暂停、审计、恢复。
>
> **不引新组件**：继续Java17、Spring Boot4、Spring Data Redis/Redis7 Lua、MySQL8.4、RabbitMQ3.13已在V12-M中的Spring AMQP、Spring Cloud Gateway、原生ESM Portal/Console。不引Kafka、Redisson/分布式锁框架、CDC、Seata、Nacos、真支付、Vue/React。高并发目标必须实测，**20,000请求是压测候选负载，不代表20,000 QPS已实现**。

## 1. 三种购买方式、两级缓存而不是再开一套商城

| 方式 | 用户行为 | 性能优化 | 最终权威 |
|---|---|---|---|
| V12-A 免费BASE | 免费SKU对自己APP/API领取一次 | 公共商品列表/详情可缓存 | MySQL免费领取唯一约束、BASE Slot，原路径不改 |
| V12-B 正价普通BASE | 选择套餐与APP→订单→模拟付款 | **同一套Redis商品/套餐Cache-Aside**；下单不依据缓存报价 | MySQL SKU不可变价格、订单快照、Slot和Payment事务 |
| **V12-C 高并发限量BASE** | 已上架PAID_BASE参加活动、抢购→持久Intent→Rabbit异步订单→模拟付款→权益 | **商品读缓存 + 活动读缓存 + Lua候选软库存/限流**，失败有界降级 | MySQL活动/库存/唯一Intent/Slot/订单/Outbox；Rabbit仅投递 |

“高并发套餐需要缓存”作为**基础业务设计要求**：首页分类、列表、详情、套餐报价及活动详情这些公开、热点、读多写少的数据均使用Redis缓存；**个人账户/APP权限、Token、配额余额、Ledger、订单状态、真实活动库存、支付和开通结果绝不从Redis缓存作为授权/交易权威**。缓存的库存数字只表示“剩余名额估计/可能有名额”，不显示实时可信售罄承诺。

## 2. 现有源码约束与不得直接套旧蓝图的地方

1. `MarketCatalogService`当前直接MySQL查询`categories/search/detail/offers`，`paidOffer()`使用DB`FOR SHARE`并核对已上架SKU与发布API。C在查询侧新增Cache-Aside包装，不把`paidOffer`改成Redis报价。
2. A的`fg_api_entitlement_slot`已经允许`holder_type='PROMO'`，可用于持久Intent受理时抢占APP/API/UTC月的BASE槽，Worker创单时**同一事务**把holder换成`ORDER`。既有免费/普通付费对同槽的互斥必须保留。
3. B`fg_order`存在`source='NORMAL' AND promo_intent_id IS NULL` **CHECK硬限制**，不能直接插`PROMOTION`；C的增量SQL需安全迁移此CHECK并新增`promo_intent_id`外键、索引，保留普通订单所有旧行/字段语义。源`sku_kind='PAID_BASE'`继续有效，活动价格可小于原SKU报价，但快照仅来自锁定的`fg_promotion`而非HTTP。
4. B`fg_business_outbox`、`fg_business_event_consumed`和`CommerceEvent` **被CHECK/解析限定`PAYMENT_CONFIRMED`和ORDER**；不能把`PROMO_INTENT_ACCEPTED`混投`flowgate.commerce.v12.orders`消费队列。C新建独立`fg_promo_outbox`、`fg_promo_event_consumed`并在**同一个Commerce DirectExchange**增`flowgate.commerce.v12.promo.intents/retry/poison`三队列及独立路由`promo-intent`，B原付款开通消息仍保持原队列/原同事务逻辑。
5. B订单`OrderService.pay/cancel/timeout`及`OrderTimeoutCloser`现在仅写订单和Slot，不改变库存；C须**以最小扩展**在订单来源为PROMOTION时，在同一个APP→Slot→Order→Intent→Stock锁序/事务内同步移动reserved/sold并改变Intent状态；C的促销订单仍可通过B现有`PAYMENT_CONFIRMED` Outbox和`CommerceService.process`创建`PAID_ORDER`权益，不能复制第二套支付、订阅或扣额系统。
6. M阶段保留三条独立Rabbit链（V11 legacy用量、V12 API用量、B commerce付款）。C只增第四条**活动意图队列**；不得重新引入Kafka或修改V11旧账本及M DB栅栏。

## 3. 商业边界与活动不可变快照

- 一个活动只针对**一个已上架、业务真实可执行的`PAID_BASE` SKU**，支持本月或下月SKU。活动发布时冻结：`sku_id`、`api_id`、`sku_revision`、`quota_units`、`period_policy`、**目标period_yyyymm**、标准`sku_price_cents`、活动`promo_price_cents`、币种CNY、`total_stock`、活动开始/结束UTC、每用户限购1、创建订单截止和最多15分钟付款期限。这些快照在发布后不可修改或跨月重算；营销价格不允许0元，免费仍走A。
- 示例：正价19.90元、活动9.90元、限100份、用户每场至多一份；金额分单位`long`且`1≤promo_price_cents≤sku.price_cents`；配额1～1,000,000；库存默认100，可配置上限100,000，避免恶意巨型值。
- `DRAFT→READY→RUNNING→PAUSED/ENDED` 为运营状态，要求另有`REBUILDING`防缓存丢失；READY由ADMIN明确发布并核对Redis重建成功与Rabbit队列就绪。活动可以按DB UTC时间由定时器从READY到RUNNING、RUNNING到ENDED，但**请求事务内也必须以MySQL UTC时间校验实际起止**，不能因定时器延迟允许越界受理。
- 一个用户在同活动最多一个持久Intent（`UNIQUE(activity_id,principal_id)`），不因为换APP或换请求ID绕过；Intent终态EXPIRED/CLOSED也保持这条唯一历史，**首版不允许同场再抢**。这是限购“参与一次”而非“未买到可无限刷新”的明确首版裁决。
- 同一APP/API/UTC月只许一个BASE：如果已领取免费、正价普通订单占HELD、历史已GRANTED或其他PROMO受理占HELD，则抢购事务409，Lua候选需按epoch/claimId安全补偿但不产生持久202。
- 用户看到202只能说“**抢购资格已持久受理，排队创建订单**”，不能称“已购买成功”。订单`PENDING_PAYMENT`后再付款，付款后`PAID_PENDING_FULFILLMENT`，Rabbit权益Grant完成才`FULFILLED`，下月套餐直到目标UTC月才允许Gateway授权。
- 退货保留B的模拟限制。**已经成功模拟付款的一份活动库存转为累计`sold`，即使后续退款也不自动返售**（促销已售口径是累计成交份数）；待付取消/过期返还reserved，未创单Intent过期返还reserved；已付/已开通严格B退款限制，不倒改Ledger。

## 4. 严格事务、事件与锁序总览

```text
公开商品/套餐浏览 → Redis Cache-Aside（按version/TTL） → 失效读MySQL公开投影
                                |
活动详情、真实OWNER/APP检查      ↓ Redis Lua软名额筛选（只是候选）
                                |
                     MySQL事务：APP行锁
                          → BASE Slot行锁
                          → 活动共享锁/epoch/UTC校验
                          → 该用户唯一Intent检查
                          → 条件UPDATE Stock reserved+1（权威）
                          → 插fg_promo_purchase_intent(ACCEPTED)
                          → Slot HELD(PROMO,intentId)
                          → 插fg_promo_outbox(PROMO_INTENT_ACCEPTED)
                          → COMMIT
                                |
                  HTTP202（持久Intent ID，非订单）
                                |
            Rabbit exchange flowgate.commerce.v12 / promo-intent
                       独立quorum主/Retry/Poison
                                |
      事务核验Outbox/Intent+APP+Slot → 唯一PROMOTION订单
         订单PENDING_PAYMENT、Slot holder=PROMO→ORDER
                     Intent ORDER_READY、Consumed
                                |
                   普通OrderService.simulate-pay
          APP→Slot→Order→Intent→Stock，CAS付款唯一收据
          **reserved--，sold++与原PAYMENT_CONFIRMED Outbox同事务**
                                |
          B独立Rabbit commerce orders付款消费者
       Grant + Subscription + Balance + Slot GRANTED
                                |
                    Gateway额度准入 + Ledger
```

**统一行锁规则**：所有同APP操作先`fg_application FOR UPDATE`→`fg_api_entitlement_slot FOR UPDATE`；有订单时锁`fg_order`再锁`fg_promo_purchase_intent`和`fg_promo_stock`。无订单的受理/Intent Timeout可先锁Intent再Stock；Promotion行只读使用`FOR SHARE`核验版本与状态，独立重建时`FOR UPDATE`阻断旧事务。**库存Stock始终在链路最后锁**，旧B普通订单不依赖促销Stock。Worker尚无订单时先锁Intent，创建成功后此事务内不需要反过来等待已经存在的Order行；重复事件若订单已存在，使用唯一`promo_intent_id`非锁定核实后直接幂等返回。实际SQL锁图和死锁重试细节见[交易不变量与故障合同](v12-c-concurrency-transactions.md)。

## 5. SQL权威数据模型与B兼容增量

**C最小新增5表**（均为MySQL8.4 InnoDB，仅全新隔离或经明确迁移批准的库执行）：

| 新表 | 字段与索引关键项 | 不变量 |
|---|---|---|
| `fg_promotion` | id PK,sku_id FK, api_id,sku_revision,period_yyyymm,period_policy,quota_units,standard_price_cents,promo_price_cents,currency,begins_at,ends_at,status,epoch,version,order_creation_ttl_seconds,pay_ttl_seconds,created_at/updated_at, `ix_status_window` | SKU/价格/配额/月份发布后不可变，活动时间和库存有限；epoch单调增加 |
| `fg_promo_stock` | promotion_id PK/FK, total_stock, reserved, sold,version | `CHECK(reserved>=0 AND sold>=0 AND reserved+sold<=total_stock)`；条件SQL原子预留/释放/售出 |
| `fg_promo_purchase_intent` | id PK, promotion_id/principal_id/app_id/api_id/sku_id/period, client_request_id, request_fingerprint, status, order_id NULL UNIQUE, deadline_at,created_at/updated_at,version | `UNIQUE(promotion_id,principal_id)`；`UNIQUE(principal_id,client_request_id)`只应用于C域；原principal只能拥有本人Intent；有订单后不可再为同一Intent建第二单 |
| `fg_promo_outbox` | id,event_id UUID UNIQUE,aggregate_id intent FK, event_type固定PROMO_INTENT_ACCEPTED,payload_json JSON,version,status,lease/attempt/next_attempt/last_error | MySQL接受事务同写；不可填B的fg_business_outbox |
| `fg_promo_event_consumed` | event_id PK, intent_id UNIQUE, payload_sha256,result, consumed_at | 同事务“已处理或有效终态忽略”标记；Rabbit重投无重复订单 |

**对B一处安全ALTER**：老B `fg_order.source='NORMAL' AND promo_intent_id IS NULL` 必须改为`(NORMAL,NULL) OR (PROMOTION,非NULL)`；先用`INFORMATION_SCHEMA.TABLE_CONSTRAINTS + CHECK_CONSTRAINTS`识别旧**未命名CHECK**，再由校验过的安全DDL迁移工具在维护窗口有条件DROP旧CHECK、ADD命名`ck_fg_order_source_c`和`FOREIGN KEY(promo_intent_id) REFERENCES fg_promo_purchase_intent(id)`。不能硬编码猜`fg_order_chk_N`或无检查随意删除；MySQL DDL不是单个业务事务，脚本必须幂等/可诊断中断重入；先保留只NORMAL的应用流量并确保可回滚/备份。B普通订单旧行保持不变，后续INSERT默认NORMAL、NULL仍有效。

**不碰**`fg_business_outbox`的PAYMENT_CONFIRMED硬CHECK，B订单/退款/权益原有表不另开同义账本；C工作只改B必要的`OrderService.pay/cancel/timeout`与`SimulatedRefundService`对于PROMOTION行的分支，绝不改变普通订单行为、原付款收据以及Ledger历史事实。

## 6. Redis缓存架构（必须有，不是额外优化选做）

两类数据必须按不同一致性边界处理：

1. **公开只读商品缓存（通用PAID_BASE/FREE_BASE套餐）**：`fg:c:catalog:v1:categories`、`fg:c:catalog:v1:list:<sha256(query,category,after,limit)>`、`fg:c:catalog:v1:detail:<listingId>`、`fg:c:catalog:v1:offers:<listingId>`，只缓存已上线公开投影：名称、摘要、文档、SKU单位/币种/标价、可售期策略和商品revision。禁止缓存包含账户、订单/Token、每用户是否可领取、Gateway权限、QuotaBalance、实时可抢余量；`purchasable`需单独实时判定。
2. **促销活动读缓存**：`fg:c:promo:v1:public:<activityId>`与有界列表页，缓存冻结价、名额总数、UTC时间与运营状态快照/epoch；TTL短，显示“活动状态以提交时核验为准”。缓存详情的价格**不可直接喂给INSERT订单**。
3. **高并发候选软库存/限流**：`fg:c:promo:gate:{activityId}:epoch`、`:remaining`、`:state`、`:candidate:<opaqueRequestToken>`及用户短租约（仅哈希/ID，不存敏感Token）。Redis Lua在同一个短脚本内检查epoch、gate READY、同一用户/请求临时租约与remaining>0，原子`DECR`生成**候选资格**；MySQL Conditional UPDATE是唯一真实名额增加。Redis剩余数可以悲观欠卖，不能代表MySQL真实可售。DB回滚的Lua补偿只在原epoch和token仍匹配时最多+1；DB已COMMIT则不退软名额。
4. **Redis故障策略**：公共商品缓存连接失败→限量/熔断/并发限额的DB只读回源，不能无上限直冲MySQL；活动抢购Lua Redis不可用/缓存epoch不存在/REBUILDING→**503 CACHE_NOT_READY**，不开启直接绕过Redis向DB抢购的无限降级。支付、已受理Intent回查、订单明细与用户Gateway授权不依赖缓存可用。
5. **失效和并发回源**：管理员改类目/上下架/新SKU/活动状态事务**提交后删除对应cache键并记录失败**；TTL（建议类目120±30s、商品列表20±5s、详情30±10s、活动详情3～5s）兜底；本机单飞与Redis短租约避免热点过期形成击穿，锁用token CAS释放，未拿到锁者短等重查或有界DB回源。不存在商品404小TTL（建议3s）；列表页最多`limit<=20`、搜索词<=50，限制可缓存页数和缓存体积避免键空间爆炸。大量不同关键字不作为缓存热点。
6. **防脏写覆盖**：回源结果带`listing.revision`/`promotion.version`；只有缓存未命中且Redis版本代次相符才原子写入，旧回源在“失效/新版本提交”后不能再覆盖新版本，可采用Redis generation键+Lua CAS写入；所有公开展示仍可短时间最终一致，但**下单/活动事务不读取缓存做权威判定**。即使缓存删除/代次更新失败，TTL仍限制展示陈旧窗口，且管理诊断显示失效失败。
7. **重建活动软库存**：不能用`INCR`补偿试图与MySQL跨系统强事务。管理员或调度器把DB活动置`REBUILDING`、增加epoch（`fg_promotion FOR UPDATE`栅栏等待活跃共享锁），拒新请求；按DB`total-reserved-sold`计算soft remaining，在Redis以新epoch原子设置gate READY/remaining；DB CAS恢复RUNNING。旧候选在DB验epoch失败，不得修改新epoch库存。重建失败停在REBUILDING；Redis重启、FLUSHDB、过期、Lua脚本缓存失效后的EVALSHA→EVAL都需实测，**不得用Redis快照反推或覆盖MySQL库存**。

采用现有Spring Data Redis/Redis7的`RedisTemplate`与`DefaultRedisScript`，不增加Redisson。Redis官网Cache-Aside及Spring Data Redis ScriptExecutor的EVALSHA→EVAL兜底是技术参考（具体测试见C阶段合同）。参考：https://redis.io/docs/latest/develop/use-cases/cache-aside/ 、https://docs.spring.io/spring-data/redis/reference/redis/scripting.html 。

## 7. 服务端一致性承诺和用户错误语义

- **Lua候选≠202**。仅在MySQL提交了`fg_promo_purchase_intent(ACCEPTED)` + Stock reserved+1 + Slot HELD + C Outbox后返回HTTP202，且响应带真实Intent ID、活动、受理时间和可查询路径。Redis筛掉的请求返回`SOLD_OUT`(409)或`RATE_LIMITED`(429)；缓存/Redis状态缺失503，活动未开始/已结束409，用户已有Intent的同参请求优先返回原Intent/最新订单，不要错误扣第二次软库存。
- 用户持久查询`GET /portal/api/promotions/{id}/my-claim`和`GET /portal/api/purchase-intents/{id}`必须**走MySQL**且校验本人身份；同`clientRequestId`重试看Intent原始指纹，换APP/活动/请求参数409；已受理后即使Redis丢失仍能查询，响应丢失可找回。
- Rabbit意图Worker处理`PROMO_INTENT_ACCEPTED`原始Outbox，严格事件协议/版本/epoch/Intent一致性，先APP+Slot行锁，再Intent状态/期限校验，幂等插一张源为PROMOTION的`PENDING_PAYMENT`订单；`fg_order.promo_intent_id UNIQUE`，用服务端专用`client_request_id`（例如保留前缀`pr_<UUID>`，普通OrderController拒客户使用该前缀）避免和B普通客户端键碰撞。新订单价/单位/月份来自**Intent发布时的冻结活动快照**（必须与Promotion发布不可变字段一致），不是Redis/HTTP。Slot holder原PROMO→ORDER同事务，Intent ORDER_READY，Consumed同事务提交后ACK。
- 若Rabbit慢/离线导致Intent到期而未出订单，`IntentTimeoutCloser`按`deadline_at`有界游标扫描，和Worker同一APP→Slot→Intent→Stock锁序转EXPIRED、reserved--、只释放自己的PROMO槽；消费者晚到见EXPIRED只记录IGNORED_TERMINAL，不得复活。订单已创建但未付，由增强的B `OrderTimeoutCloser`按APP→Slot→Order→Intent→Stock锁序释放reserved，关闭Order与Intent同事务；付款和关单只有一个赢。
- **模拟支付先于权益开通**：增强B付款分支在`PENDING_PAYMENT`、有效DB UTC截止、来源/holder均合法下，`reserved--,sold++`、Intent PAID、1 PaymentRecord、1 PAYMENT_CONFIRMED BusinessOutbox、Order PAID_PENDING_FULFILLMENT与Transition**同一MySQL事务**；成功不会二次扣促销库存，不用Rabbit确认支付成功。B现有CommerceWorker继续消费付款Outbox并唯一Grant；活动结束**不撤销已创建订单付款窗口**，但绝不允许超过订单付款截止/目标月有效期。
- **模拟退款**：未开通先退款并按B规则释放HELD（付费已sold，**不归还活动库存**）；已开通但从未授权过可Suspend并留GRANTED；已扣额度409，历史Ledger绝不改。Promo Intent相应状态REFUNDED可在B退款同事务记录，仍保留原`sold`。原已付记录如再投Rabbit只能发现REFUNDED后忽略，不能开新权益。
- **不变量**：`0<=reserved`、`0<=sold`、`reserved+sold<=total`；每一个reserved必须被一个未付款有效Intent/Order解释，每一个sold必须由同一活动一个已付款（含已退款）Order解释；同活动每principal最多一个Intent、每Intent最多一Order；同APP/API/月最多一BASE；同B订单最多一付款收据、最多一PAID_ORDER Grant；重复消息和重试不会重复移动库存。
- **性能约束**：仅Lua/缓存读是高速路径；真实成功者必须进MySQL事务。热点库存行最多售100的高争抢通过前置Lua把绝大多数请求挡在DB外，**不能假设20k同时在DB UPDATE**。可有谨慎限流、瞬时欠卖，不允许超卖或丢失已受理事件。C性能压测分热缓存命中、活动候选、全链路履约三个维度分别报告。

## 8. 发布门槛与工程交接

- 新C代码以`FLOWGATE_V12_PROMOTION_ENABLED=false`与`FLOWGATE_V12_CATALOG_CACHE_ENABLED=false`独立开关**默认OFF**；关闭或C表不存在时V12-B普通支付、A免费、M Rabbit三链都继续正常，Portal/Console促销入口显示DISABLED/SCHEMA_NOT_READY而非HTTP500。
- C业务先有完整B付款/退款和M Rabbit运行就绪，再发布活动。若任一核心Broker/DB/Consumer不Ready，新受理返回503；已持久Intent的查询、已创建订单的支付/关闭/受控恢复不能因为Redis缓存故障丢失业务事实。
- **先对齐PR #18/#19奇怪的同文件树结构**：基线M功能HEAD而不是从PR#18文档分支做未核对的合并；C只创建独立设计分支和Draft PR，不修改老PR/base/main；M8真实复审和负责人人工验收仍单列。
- 源码/SQL详细锁序与失败场景：[交易设计](v12-c-concurrency-transactions.md)；两类缓存及重建：[缓存合同](v12-c-cache-consistency.md)；Codex编码阶段：[C0～C9任务](../progress/v12-c-high-concurrency.md)与根目录`CODEX_V12_C_EXEC_PROMPT.md`。**上述全部是设计，不是本轮完成的代码或测试。**
