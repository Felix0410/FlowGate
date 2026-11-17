# FlowGate V12-B｜普通API订单、模拟支付、权益可靠开通总体技术设计（2026-11-10）

> **定稿范围：仅设计，尚未实现/未迁移/未测试V12-B。** 父基线为 `feat/v12-a-portal@f2026c59c7a7359e14e3ecd1d10f0c1a84cac8d8`。A阶段Portal、免费SKU、V12 API级月余额/授权Ledger及RabbitMQ用量已编码并有定向证据；**A的P1-A01/A02/A03尚未修复，纳入本阶段B0开发与验收**。此文件优先细化旧[交易与抢购蓝图](v12-commerce-order-promotion-blueprint.md)中关于B的提案；[RabbitMQ消息修订](v12-rabbitmq-adaptation.md)优先于旧文中的Kafka新业务描述。本阶段没有真实支付、抢购、优惠券、套餐自动升级或V12-C商品库存。

## 一、业务目标与用户体验

普通Portal用户不持有ADMIN权限，通过“API详情→选择付费SKU和本人应用→确认订单→模拟支付→等待权益开通→查看月额度→真实Gateway调用→查看订单与用量”完成完整链路；管理员负责可售付费SKU/运营订单、支付与权益状态、异常和有权限的补投，不直接替用户建应用。普通免费SKU仍走A既有的`/portal/api/free-grants`，**零元商品不伪装模拟支付订单**。

**角色**：PORTAL_USER只能对自己的Developer OWNER、APP和订单操作；ADMIN能创建付费SKU、只读订单/对账/受控重排；OPERATOR/VIEWER只读授权范围；APP_CALLER只用Gateway、不能访问用户/订单；旧ADMIN Token不能作为Portal购物会话。所有对象读写由服务端从Principal取user并复核APP归属，跨用户订单ID返回404。

**范围定稿**：
1. SKU为单一API、BASE授权，报价为服务器冻结的CNY整数分；一个APP/API/UTC月仅一个BASE槽。
2. 支持`CURRENT_MONTH`与`NEXT_MONTH`两个显式商品期间策略，购买时冻结具体`period_yyyymm`。前者当月开通；后者可预先开通下月订阅但**下月UTC起才允许调用**。
3. 付款和退款均为**模拟动作**，只有MySQL收据，绝不接支付宝/微信/真实银行，不发真实资金交易通知。
4. 付费前预占`fg_api_entitlement_slot`为HELD；付款事务写收据、状态和业务Outbox；Rabbit异步消费开通Grant/Subscription/Balance，订单FULFILLED；失败继续显示等待或人工诊断。
5. 已付款但未履约允许受控模拟退款并释放自身HELD，已履约且完全未使用允许退款并停用权益，但BASE槽保持GRANTED；已经使用一单位就不接受自动模拟退款，**不修改旧流水/不返还已扣额度**。
6. V12-B不预扣促销库存、不建`fg_promotion`或`fg_promo_stock`。保留`source`/nullable`promo_intent_id`字段扩展点给V12-C，B只允许NORMAL且不创建促销意图。

## 二、V12-B0合并整改V12-A三项P1（强制先于B订单开发）

| B0任务 | 对应既有问题 | 必须达成的可运行事实 |
|---|---|---|
| B0-1 A Rabbit坏消息分类与有界重试 | P1-A01 | Jackson3无效JSON与永久Ledger/Outbox冲突进入持久Poison；MySQL短故障有界重试，真正恢复后去重；损坏Outbox停止自动重领且可查询/审计；不能无限5秒回炉 |
| B0-2 真Rabbit故障小矩阵 | P1-A02 | Broker停机→恢复、持卷容器重建、publisher confirm/mandatory return失败、真正TTL retry和Poison、quorum与feature flags现场查询；Ledger授权仍MySQL权威 |
| B0-3 V12默认OFF旧Console安全空态 | P1-A03 | **旧V11无14张新表+V12开关OFF**时新市场页给DISABLED/SCHEMA_NOT_READY，不SELECT缺表、不触发500、不影响旧20个后台能力；V12已Ready则正常上架 |

B0不是已完成的事实；**Codex完成B0后必须提交新的真实证据**，才能写B1业务代码。已有`CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md`仅供复用技术细节，**不再单独派工**；本轮B的统一执行任务见`docs/progress/v12-b-order-payment.md`及根`CODEX_V12_B_EXEC_PROMPT.md`。B0无须100并发/旧D大型性能，必要时补少量旧V11 B/C关键回归以判定未破坏。

## 三、权威数据、表结构、与A数据库兼容

全部在独立增量SQL `sql/flowgate-v12-b.sql`。**A十四表绝不DROP/清空**，B只新增下面六表并复用A的`fg_api_sku`、`fg_api_entitlement_slot`、`fg_entitlement_grant`、`fg_api_subscription`、`fg_api_quota_balance`。全部FK/UNIQUE/CHECK在MySQL8.4隔离库真实迁移与重复执行核验；表名/列名是实施合同，具体Java实体类可轻量化但不能省略数据库约束。

| 表 | 合同字段与唯一/索引 | 事实 |
|---|---|---|
| `fg_order` | `id BIGINT AUTO_INCREMENT PK`, `order_no VARCHAR(48) UNIQUE`, `principal_id`, `developer_id`, `application_id`, `api_id`, `sku_id`, `sku_revision`, `sku_kind='PAID_BASE'`, `source='NORMAL'`, `promo_intent_id BIGINT NULL UNIQUE`（C才追加FK）, `client_request_id VARCHAR(64)`, `request_fingerprint CHAR(64)`, `period_yyyymm CHAR(6)`, `effective_at/expires_at`, `price_cents BIGINT>0`, `currency CHAR(3)='CNY'`, `units_snapshot BIGINT>0`, `status`, `version>=0`, `pay_deadline_at`, timestamps | `UNIQUE(principal_id,client_request_id)`、`INDEX(principal_id,id)`, `INDEX(status,pay_deadline_at,id)`, `INDEX(app,api,period)`；Order只记录一次冻结报价/期间 |
| `fg_payment_record` | `id PK`, `order_id NOT NULL UNIQUE FK`, `provider='SIM'`, `provider_ref VARCHAR(80) UNIQUE`, `amount_cents`, `currency='CNY'`, `confirmed_at` | 一笔订单最多一份模拟付款收据；金额来自订单，不相信浏览器 |
| `fg_business_outbox` | `id PK`, `event_id CHAR(36) UNIQUE`, `event_type`, `event_version`, `aggregate_type`, `aggregate_id`, `aggregate_revision`, `payload_json JSON`, `status NEW/IN_FLIGHT/SENT/FAILED/FAILED_PERMANENT`, `attempt_count`, `next_attempt_at`, `lease_owner/lease_until`, `last_error_code`, timestamps | `UNIQUE(event_type,aggregate_type,aggregate_id,aggregate_revision)`与`INDEX(status,next_attempt_at,lease_until,id)`，同一订单状态转换只投一个事实 |
| `fg_business_event_consumed` | `event_id CHAR(36) PK`, `event_type`, `aggregate_id`, `payload_sha256`, `result APPLIED/ALREADY_APPLIED/IGNORED_TERMINAL`, `consumed_at` | Rabbit重复消息无法重复开通；写入与订单/权益状态同DB事务 |
| `fg_order_transition` | `id PK`, `order_id FK`, `from_status`, `to_status`, `reason_code`, `actor_type/actor_id`, `created_at`, `INDEX(order_id,id)` | 追加式可解释审计，不覆盖历史订单状态 |
| `fg_simulated_refund` | `id PK`, `order_id FK UNIQUE`, `refund_ref UNIQUE`, `amount_cents`, `reason_code`, `status='CONFIRMED'`, `confirmed_at` | 模拟退款不可变事实；非真实资金回退 |

**不创建额外的幂等键表**：`fg_order UNIQUE(principal_id,client_request_id)` + 订单指纹足够；也不创建全局预付钱包或分布式事务框架。SQL示例迁移目标顺序`V1→V11-A/B→V11-C→V12-A→V12-B`，但由隔离启动器以源码中的实际脚本名确定，不能凭概念名误操作。C需要引用`promo_intent_id`时才加相关FK与活动表。

**A库关键真实约束**：
- `fg_entitlement_grant.source_type`已经允许`PAID_ORDER`，`UNIQUE(source_type,source_id)`；B写`source_type='PAID_ORDER',source_id=fg_order.id`，不向旧`fg_subscription`或旧Ledger写新权益。
- `fg_api_entitlement_slot`已经允许`holder_type='ORDER'`与HELD/GRANTED；普通订单正常锁槽，已付款但权益暂未开通时**保持HELD**。
- `fg_api_subscription.status`实际允许ACTIVE/SUSPENDED/EXPIRED；已使用为0的模拟退款将此状态改为SUSPENDED并记录一份唯一`fg_simulated_refund`，**不需要擅自改A已存在CHECK**。已Grant的slot保持GRANTED，不能退回AVAILABLE。
- `fg_api_quota_balance`与`fg_api_quota_ledger`不删除、不回填历史；`ApiQuotaChargeService`的`status='ACTIVE'`限制本来就会拒绝退款后SUSPENDED的后续调用。
- `fg_api_sku`当前DDL支持`FREE_BASE/PAID_BASE`和CURRENT_MONTH/NEXT_MONTH，但A的MarketService只创建/展示免费：B仅放开**管理员创建全新PAID_BASE且price_cents>0**的入口，按不可变SKU报价；A原`claimOffer`仍只接受FREE_BASE。

**兼容旧订阅**：与A的免费领取保持相同保守原则，同APP目标UTC月若已有V11旧`fg_subscription`的重叠生效期间，则B创建付费订单前返回`409 LEGACY_SUBSCRIPTION_CONFLICT`；不跨池迁移旧额度。不同APP的Legacy与V12业务仍可并存，Gateway同一route只选一种模式。

**可售条件**：真实上市`listing.status='LISTED'`、category ACTIVE、SKU ACTIVE、价格正数、APP与Developer ACTIVE、用户OWNER、目标API已在当前可信发布快照具备V12 APP_API计量且全部节点支持；每次创建订单时后端重验，不能只凭页面历史结果。订单创建后下架/改列表文案**不影响订单原快照**，但若API真实路由下线、无法履约要保留已付订单在可解释待恢复/退款状态，不假开通可用。

## 四、业务状态机（事件、资格与订单拆开）

```text
              MySQL持BASE槽HELD并写订单
                           |
                           v
                   PENDING_PAYMENT
                   /           \
       模拟支付CAS/              \ 取消/到期CAS
                 v               v
     PAID_PENDING_FULFILLMENT  CANCELLED / CLOSED_TIMEOUT
                 |                    |
       Rabbit PAYMENT_CONFIRMED       +--仅本人HELD槽→AVAILABLE
                 |
                 +--权益Worker成功--> FULFILLED (slot GRANTED, 1 Grant/Balance)
                 |
                 +--暂时依赖错误--> FULFILLMENT_RETRY --重试--> FULFILLED
                 |
                 +--不可安全履约--> FULFILLMENT_REVIEW --审核/模拟退款--> REFUNDED
                 |
          未Fulfilled的受控模拟退款 CAS --> REFUNDED (仅本人HELD→AVAILABLE)

      FULFILLED且本权益无任何Ledger记录 --模拟退款CAS--> REFUNDED
                      （订阅SUSPENDED，历史GRANT保留，slot继续GRANTED）
```

| 当前状态 | 允许动作 | 禁止动作及真实显示 |
|---|---|---|
| PENDING_PAYMENT | 付款或取消或超时关单（仅一个CAS胜出） | 未付款绝不开通付费Grant；超时不自动当付款 |
| PAID_PENDING_FULFILLMENT | Worker异步履约；受控模拟退款 | 用户**已模拟付款**但还没有可用API权益 |
| FULFILLMENT_RETRY | 自动恢复、受控退款、运营诊断 | 不释放HELD后重建同月另一单 |
| FULFILLMENT_REVIEW | 运营核查/受控退款 | 不改成下个月，不伪装FULFILLED |
| FULFILLED | 查看权益；在完全没消耗时可模拟退款 | 下月SKU即使FULFILLED也要到nextMonth开始才能通过Gateway |
| CANCELLED / CLOSED_TIMEOUT / REFUNDED | 只读/幂等查询 | 晚到PAYMENT_CONFIRMED不得复活或二次Grant |

**B时间合同**：所有决策使用MySQL UTC时钟；`CURRENT_MONTH`订单仅在距本月结束至少**15分钟**时受理，支付期限`min(createdAt + 15min,monthEnd - 5min)`，且必须晚于DB_NOW。下月订单仍然付款15分钟内，权益按下月第一秒生效；不能把异步消费时间当成新购买月份。订单即使FULFILLED，调用授权仍以`effective_at<=NOW<expires_at`判断。临近月末业务可拒绝当月新单，不自动推荐改期为“已购买”。

**锁序（不可临时改）**：
1. 已有订单的支付/关闭/退款/履约：**先用非锁定SELECT只读取不可变order的app/api/period/id定位**→`fg_application FOR UPDATE`→`fg_api_entitlement_slot FOR UPDATE`→`fg_order FOR UPDATE`，之后按需`fg_api_subscription/fg_api_quota_balance`与`fg_payment_record`、Outbox、transition。事务锁后**重新核验**order.id/immutable字段/slot holder/状态，禁止拿初次快照直接更新。
2. 创建订单：先查`(principal,requestId)`并比对指纹（同用户同参数返回原单），再锁APP→slot→复核旧V11同APP/目标月重叠订阅→冻结SKU报价→创建Order→slot=HELD(holder=ORDER,orderId)；唯一键冲突事务整回滚，**在新只读事务**查询已胜出订单比对指纹、给同参幂等结果或409。若另一个APP用相同requestId竞争，仍靠SQL唯一键判决，不泄露对方订单。
3. 已付订单的Worker保持同样app→slot→order→subscription/balance顺序。Gateway扣量会先锁APP再锁新余额；退款流程必须先持APP锁再判断Ledger，在同一DB事务内串行，避免“查询时没用，退款后又扣了一笔”。
4. 所有失效/回收的HELD槽必须CAS校验`holder_type='ORDER' AND holder_id=本订单`和`version`；Worker处理晚到事件时先查订单真实终态，不修改其他订单后来获得的槽。**不能订单超时后照事件给已关闭订单开通权益**。
5. 事务内不远程调用Rabbit/Redis/下游API，MySQL事务提交后异步Worker投递；应用行锁与slot是争抢资源，首版优先可证明正确，再在D性能实验评估吞吐优化。

## 五、Portal和管理REST契约

**普通用户Portal**：
- `GET /portal/api/catalog/apis/{listingId}/offers`：保留FREE_BASE，同时新增已上市的PAID_BASE报价，返回`skuId,kind,priceCents,currency,quotaUnits,periodPolicy,validityPreview`，所有金额和可售状态服务器提供，不因客户端发送price而更改订单。
- `POST /portal/api/orders`：请求**仅**`{applicationId,skuId,clientRequestId}`，不接受`price,units,apiId,userId,developerId,periodUtc`等自报交易事实；clientRequestId为前端同次点击生成的UUID，网络重试保持不变，新一次购买才生成新UUID。成功201`{orderNo,status:'PENDING_PAYMENT',amountCents,currency:'CNY',periodUtc,payDeadlineAt,applicationSummary,apiSummary}`；同key同参200返回同一订单（包括后续终态），同key不同参数409`IDEMPOTENCY_CONFLICT`。
- `GET /portal/api/orders?afterId=&limit=`和`GET /portal/api/orders/{orderNo}`：服务端归属+分页最大50，金额、订单状态、模拟收据简要信息、权益状态、失败原因可读；跨用户404。
- `POST /portal/api/orders/{orderNo}/simulate-pay`：仅PENDING_PAYMENT、DB当前时刻早于payDeadline、必须owner，成功200`{orderNo,status:'PAID_PENDING_FULFILLMENT',paymentStatus:'SIMULATED_CONFIRMED'}`；重复对同一已支付订单返回当前真实付款/履约状态且不再出收据/Outbox；取消/过期/退款后的付款409`ORDER_NOT_PAYABLE`。
- `POST /portal/api/orders/{orderNo}/cancel`：仅尚未付款PENDING_PAYMENT、可幂等重复显示原CANCELLED；已付款应走受控退款而非取消。
- `POST /portal/api/orders/{orderNo}/simulate-refund`：B末期由明确的`canRefund`后端资格决定；未履约PAID_PENDING/RETRY/REVIEW直接模拟退款并释放HELD；FULFILLED且余额`remaining==total`且**不存在任何本订阅有效Ledger**方可退款并SUSPENDED订阅、slot仍GRANTED；已扣量409`USED_ENTITLEMENT_NON_REFUNDABLE`；只保留模拟收据与审计，不发真实银行支付数据。
- `GET /portal/api/subscriptions,quota,ledger,usage`沿用A既有API；B增加`sourceOrderNo`等非敏感归属信息，仅对本人可见。

**管理Console**：
- `POST /admin/market/listings/{id}/skus`增加`PAID_BASE`/priceCents>0、CURRENT_MONTH/NEXT_MONTH（ADMIN唯一可写），原FREE_BASE行为兼容；商品上架/下架沿用A门禁；已引用SKU的单价/单位只读，改价创建新SKU，不能覆盖历史报价。
- `GET /admin/orders?status=&afterId=&limit=`、`GET /admin/orders/{orderNo}`：运营查询真实订单、金额、SIM收据、已开通/待重试以及失败码（不泄漏Token、密码、内部密钥）；OPERATOR/VIEWER只读，ADMIN也不能改历史price/units/period。
- `GET /admin/commerce/readiness`：独立安全开关、V12所需新表、Rabbit Broker/队列/真实worker或未知状态，不用“前端开关”冒充READY；无新表时不500，老V11仍可使用。
- `GET /admin/commerce/reconciliation?day=`：独立只读B订单收据与权益/slot/Outbox/Consumer完整性，当前UTC日只能`PROVISIONAL`，在处理中的已付款状态是PENDING而不是MISMATCH，孤儿订单/重复Grant/无法解释的持槽明确INTEGRITY_GAP。需有界SQL索引分页/范围，避免全表扫描大账本。
- `POST /admin/commerce/events/{eventId}/replay`：仅ADMIN、审计、CAS、等待期与消费去重复核，**重放原eventId**，不重新出一笔模拟支付或Grant。操作危险需确认弹窗与可解释原因。

**HTTP错误规范**：未登录401、合法身份跨人对象404、无OWNER或身份停用403/404、不合法输入400、BASE已HELD/GRANTED 409、过期关闭/付款争用409、依赖不可用503；400/409不能泄漏用户存在性、SKU内部SQL或Rabbit消息体。

## 六、RabbitMQ Commerce链路设计（不复用A用量队列）

```text
MySQL payment CAS + unique SIM receipt + ORDER transition + BusinessOutbox PAYMENT_CONFIRMED COMMIT
             |
        BusinessOutboxPublisher持有租约
             | Rabbit persistent publisher confirm AND no mandatory return
             v
flowgate.commerce.v12 (direct exchange)
             |
flowgate.commerce.v12.orders (quorum, manual ACK)
       |成功 MySQL：[slot owner检查 + unique PAID_ORDER Grant + subscription + balance + order FULFILLED + consumedEvent] COMMIT -> ACK
       |临时DB故障：确认持久 retry 消息副本 -> ACK 原件；retry TTL -> orders
       |永久协议/权威冲突：reject(false) -> poison quorum；不静默丢弃
       |消息ACK后进程崩：依靠eventId和业务唯一键安全重放
```

- B使用**新**`CommerceRabbitConfiguration`、`BusinessOutboxPublisher`、`OrderFulfillmentWorker`、`CommerceEventCodec`，交换机`flowgate.commerce.v12`，业务主队列`flowgate.commerce.v12.orders`，5秒TTL重试`flowgate.commerce.v12.retry`，Poison`flowgate.commerce.v12.poison`；使用同Rabbit Broker但与A usage完全不同的队列/消费线程、Outbox与消费去重表。旧V11 C Kafka完全保留，不新增B Kafka Topic。
- B只允许已知版本的`PAYMENT_CONFIRMED`事件；消息含`eventId,eventVersion,type,orderId,orderVersion,occurredAtUtc`，**不带金额、Token、用户PII或可伪造授权单位**。Worker始终从MySQL订单和SKU快照、持槽事实重查，不能直接凭事件载荷发权益。
- Broker不在线时，已模拟付款订单仍`PAID_PENDING_FULFILLMENT`且MySQL Outbox NEW/FAILED；用户能查原订单。恢复自动投递后终态FULFILLED，不丢失原支付事实。
- Publisher对Broker ACK/mandatory return使用关联消息ID，超时/NACK/return保留事件可重投。租约30秒、单轮最多2且每条confirm超时5秒是当前A的实现参考，B按实际验证无租约超时竞态；`SENT`但未被消费的事件有300秒grace及**实例启动后冷却期**、ADMIN审计/CAS原eventId重排，已被消费不可重排。
- Consumer事务提交才basicAck，临时依赖故障可恢复重试且必须有**明确最大次数**，未知Runtime不得无限进入TTL回炉；错误JSON/事件版本冲突/权威Ledger及订单不匹配进隔离Poison，保留事件的可追溯摘要和审计。
- B0必须先证明实际Rabbit quorum队列的`x-dead-letter-strategy=at-least-once`、`x-overflow=reject-publish`和`stream_queue` feature flag在**真实Broker**有效；Quorum单节点的容器持卷重建验证不代表多节点Rabbit高可用。
- `FLOWGATE_V12_COMMERCE_ENABLED=false`与`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED=false`默认关闭；关闭时不建B队列、不定时关闭用户旧订单、不读B新表、不隐式迁移；订单创建/模拟支付在未ready时**fail-closed且不产生HELD槽**，只读运营页显示未就绪。正式开启必须需要A Portal/Quota、B表、Rabbit、Worker及隔离运行授权。异步处理临时故障可用MySQL Outbox补投，不因为Broker短暂中断丢付款事实；若commerce未就绪不受理新交易。

## 七、模拟退款的处理一致性

- 用户B退款资格在MySQL事务内确定，不能依赖前端显示“可退”。简单同步**模拟退款**即可，因为没有真实外部资金接口；不新增第三方支付系统或回调。
- 对已付款未FULFILLED：APP→slot→order持锁，在相同事务创建`fg_simulated_refund`唯一记录并将订单置REFUNDED、仅本单holder的HELD→AVAILABLE、追加状态流水；晚到付款事件在Worker看到REFUNDED只做幂等`IGNORED_TERMINAL` ACK，不能覆盖后来订单holder。
- 对FULFILLED：持同样APP→slot→order→Subscription/Balance的锁序，`remaining==total`与无任何此订阅Ledger（历史0）二重验证且应用锁防并发授权；将订阅置SUSPENDED、订单REFUNDED、保留Grant与原余额/slot GRANTED，追加模拟退款与审计。已经授权过一次即使额度又以其他方式恢复也**拒绝退款**。
- 同单两次退款返回原`fg_simulated_refund`而不新建；若refund vs Worker同时争抢，只有先获得锁的分支改变订单；所有已付款/退款单保留收据，运营可以解释处理经过。
- B阶段不支持真实现金原路返还、优惠券退回、活动库存返还。此处不擅自执行B/C促销联动。

## 八、测试与安全终点

1. **B0先完成且能证明**非法Rabbit消息Poison/MySQL恢复/真实Broker重启/旧V11未迁移Console友好提示。B0失败不得进入B订单开发。
2. 在全新隔离库运行V1→V11→C→V12-A→B增量DDL两次，检查真实FK/UNIQUE/KEY和旧数据只读。两Control并发同幂等键同参同一Order、换SKU/APP返回409；两个应用同用户同key不能双订单；100次POST支付只一个SIM收据。
3. 当前月/下月SKU真展示、下架仍保持已冻结订单快照、FreeBASE与PAID_BASE竞争同APP/API/月份槽（免费占GRANTED则付费不得扣款）、两个用户越权查单404；ADMIN/OPERATOR/VIEWER写权限分离。
4. `simulate-pay` vs `timeout/cancel`真实并发、重复退款 vs Worker开通，所有结果只有一个有效状态；Kafka V11原消费不受B Rabbit影响。
5. 暂停Rabbit后确认模拟付款订单存在、Outbox可见且用户看见`PAID_PENDING_FULFILLMENT`；恢复后最终一份PAID_ORDER Grant、一份订阅/余额、Order FULFILLED；100次同事件重放不增加权益；坏JSON和永久权威冲突进入Poison，临时MySQL离线可恢复，无永久循环。
6. 真实Gateway在FULFILLED且有效月内按PAID_BASE单位扣新API池；未付、已退款、未来月未生效、额度耗尽均拒绝。旧V11 Ledger/APP月池完全不变，Rabbit异步用量不参与实时扣量。
7. 真实Chromium 1280/375完成普通买单、模拟支付、待开通→FULFILLED→APP Token调用、订单和权益展示、受控退款状态；Portal不能输入数据库ID/策略ID/JSON购买。Console订单/失败/重排有真数据和中文说明。
8. 隔离MySQL/Redis/Kafka/Rabbit/双Gateway/Control测试有真实SHA、退出码、前后计数、失败历史，不能用设计文本替代实测或声称B已上线。正式性能/20k抢购都属于V12-C/D，当前不执行。

**判定唯一**：订单创建=持槽且MySQL事务成功；模拟支付成功=已收据与Outbox提交、但尚未获权；权益履约=PAID_ORDER Grant+APP/API月订阅和余额的MySQL事务提交；API可调用=有效期间且Gateway真实授权通过。Rabbit at-least-once不是跨系统exactly-once。
