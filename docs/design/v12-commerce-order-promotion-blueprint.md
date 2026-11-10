# FlowGate V1.2｜V12-B订单与V12-C限时抢购详细设计蓝图

> **状态：详细设计定稿，暂不生成V12-B/C Codex实施指令、不编码、不迁移、不宣称通过。** 适用前置`docs/design/v12-architecture-decisions.md`、`docs/design/v12-transaction-invariants.md`、`docs/progress/v12-a-portal-free-subscription.md`。如有实现冲突以最近ADR为准。此文档只是把后续阶段业务合同锁定，防止V12-A完成后临时重选订单方案。

## 1. 先定义商品/权益/订单/抢购到底是什么

- `fg_api`、`fg_api_listing`：可实际调用的API与运营展示；`fg_api_sku`：API月BASE授权单位与货币报价的不可变版本。
- `fg_api_entitlement_slot(APP,API,UTC月)`：一个APP对一个API一个月仅一个BASE的**预占/已授予**位置。所有免费领取、普通订单、秒杀意图竞争这一槽，任何一条流程不能绕过它。
- `fg_order`：用户针对指定APP/API/月某个SKU的**一次交易**，只能根据服务端商品快照生成；价格以**整数分**固定、付款模拟不使用真钱。
- `fg_payment_record`：**模拟结算已确认**的不可变证据，与真正资金划转无关。
- `fg_entitlement_grant`：权益开通的不可变唯一来源，`FG_API_SUBSCRIPTION`和余额一起构成可调用资格；它不是订单，更不是Kafka消息。
- `fg_promo_purchase_intent`：**已持久受理的抢购资格**，可以尚无订单；`fg_promo_stock`是总额/预留/已售的最终数据源。
- `fg_business_outbox`与`fg_business_event_consumed`：订单/交易异步投递与幂等；仅同Broker不共用C/V12-A用量Consumer或其Topic。

## 2. V12-B的SQL表与唯一约束（`sql/flowgate-v12-b.sql`拟议）

| 表 | 必需字段与DB规则 | 不变量/索引 |
|---|---|---|
| `fg_order` | id BIGINT PK、order_no UNIQUE、principal_id、developer_id、application_id、api_id、sku_id、period_yyyymm、sku_revision、price_cents、units_snapshot、currency='CNY'、source NORMAL/PROMOTION、promo_intent_id NULL、client_request_id、request_fingerprint、status、version、expires_at、created_at/updated_at | `UNIQUE(principal_id,client_request_id)`、`UNIQUE(promo_intent_id)`（NULL允许非活动订单）；`INDEX(principal_id,id)`和`INDEX(status,expires_at,id)`；状态锁CAS，不信客户端price |
| `fg_payment_record` | id PK、order_id FK UNIQUE、provider='SIM'、provider_ref UNIQUE、amount_cents、confirmed_at | 模拟成功一个订单恰好一张唯一收据，金额必须和订单快照一致 |
| `fg_business_outbox` | id PK、event_id CHAR36 UNIQUE、event_type、aggregate_id、payload_json、status NEW/LEASED/SENT/FAILED、next_attempt_at、lease_owner/lease_until/attempt_count、event_version、created_at | `INDEX(status,next_attempt_at,lease_until,id)`；MySQL事务落库后Kafka至少一次投递 |
| `fg_business_event_consumed` | event_id PK、aggregate_type、aggregate_id、payload_sha256、consumed_at | 同一事件重复消费不重复处理；更重要的Order/Grant/Slot唯一键仍在 |
| `fg_order_transition` | id PK、order_id FK、from_status、to_status、actor_type/actor_id、reason_code、created_at | 只追加不覆盖历史，供审计/故障追踪 |
| `fg_simulated_refund`（若B受控退款启用） | id PK、order_id UNIQUE、refund_ref UNIQUE、status、reason_code、created_at/confirmed_at | **无真金流**，禁已使用授权单位直接“退”Ledger |

**数据库操作合同**：
- 普通下单：从Portal会话取user，校验开发者OWNER和App ACTIVE；锁APP行→`fg_api_entitlement_slot FOR UPDATE`→校验当前BASE未HELD/GRANTED→读取在售不可变SKU/真实API published V12能力→冻结UTC月与到期、金额→插订单`PENDING_PAYMENT`，槽置HELD(holder=ORDER,holderId=orderId)→同事务提交。相同`(userId,clientRequestId)`并发先按已提交订单校验指纹，返回原订单；不同参数409。没有持槽成功，不能返回“订单已创建”。
- `simulate-pay`：同一锁序APP→slot→order；条件`status=PENDING_PAYMENT AND expires_at>DB_NOW`，插`fg_payment_record`唯一收据、写`fg_business_outbox PAYMENT_CONFIRMED`与transition，订单→`PAID_PENDING_FULFILLMENT`，**全在一个MySQL事务**；不得同步创建权益或先发送Kafka。
- 过期取消：同一锁序锁APP→slot→order，条件`PENDING_PAYMENT`（超时则`expires_at<=DB_NOW`），订单→`CLOSED_TIMEOUT`/取消，清除仅当前订单holder的HELD→AVAILABLE；对应关闭事件同事务；与模拟支付必须只能一个方向提交成功，另一个看到409/原终态。
- Fulfillment：消费`PAYMENT_CONFIRMED`前从MySQL原始订单重读并校验仍为PAID_PENDING、槽holder未被释放/换人、SKU快照、month未过期；同事务写`fg_entitlement_grant(source=PAID_ORDER,sourceId=orderId)`，V12`fg_api_subscription`、`fg_api_quota_balance`及slot GRANTED、订单FULFILLED与audit/Consumed；提交后才ACK。重复Delivery或多Worker只产一份Grant与余额。
- 消息失败：短暂DB或Kafka错误自动有界退避；永久字段冲突暂停隔离并运营审计；`SENT`但没消费的事件提供超过等待期+ADMIN审计+CAS原eventId重投；订单可见`PAID_PENDING_FULFILLMENT`/RETRY，不伪装为完成。
- 时间：只依数据库UTC确定有效截止；当月BASE购买需要距UTC月末至少配置的售卖保护窗（建议15分钟），超限禁止新订单；支付成功但权益无法在月内开通时进入专门`FULFILLMENT_REVIEW`受控模拟退款，**不得默默改下一个月**。
- 首版货币只有`CNY`、整数分，静态SKU价格前端只能展示不能决定交易金额。超时关闭不产生真实银行退款。

### B状态转换表

| 源状态 | 事件 | 目标状态 | 资金/库存/权益副作用 |
|---|---|---|---|
| 无 | 持槽且创建订单事务成功 | PENDING_PAYMENT | 锁BASE槽；普通订单无活动库存 |
| PENDING_PAYMENT | simulate-pay赢得CAS | PAID_PENDING_FULFILLMENT | 一笔SIM付款收据+PAYMENT_CONFIRMED Outbox |
| PENDING_PAYMENT | cancel/timeout赢得CAS | CANCELLED/CLOSED_TIMEOUT | 释放当前holder的HELD槽 |
| PAID_PENDING_FULFILLMENT | Worker事务成功 | FULFILLED | 一份Grant、订阅和余额，slot GRANTED |
| PAID_PENDING_FULFILLMENT | 临时依赖不可用 | FULFILLMENT_RETRY | 仍占HELD，不假开通，不丢收据 |
| FULFILLMENT_RETRY | 重放成功 | FULFILLED | 唯一Grant，无重复开通 |
| 任何已关闭终态 | 重复支付/晚到Worker | 原终态 | 禁再次开单、续活或退款双执行 |
| FULFILLED | 模拟退款（**仅若B另行实现且未使用**） | REFUND_REVIEW/REFUNDED | 见下节严格边界；不能删除Ledger/重新售卖历史月BASE |

### 模拟退款的安全最小方案

用户任务要求“模拟退款与权益处理”，但不能为凑功能开放可回滚Ledger的危险接口。建议B1～B3主链路稳定后单独小段B4（是否实施由负责人确认），仅允许：
1. 已模拟付款但**尚未Fulfilled**：持APP→槽→订单锁，令订单进入`REFUND_PENDING`并阻止Worker开通；创建唯一模拟退款任务；确认后标REFUNDED，释放HELD槽；若已进入Fulfilled则冲突拒绝，改走第二种检查。
2. 已Fulfilled但**未使用**：同事务锁APP→槽→订单→V12 Balance并确认`remaining==total`且不存在任何对应Ledger，标订阅`REVOKED`、退款记录确认；原Granted槽仍保留（不允许当月重售同APP/API），历史Grant/余额行不删除。
3. 已经使用任何授权单位：首版返回`409 USED_ENTITLEMENT_NON_REFUNDABLE`，不能改原Ledger、不能直接加回余额、不能谎称银行已退款。
4. 促销`sold`对已付且退款的商品**默认不再返售**，避免用户返还后第二次活动成交让历史口径混乱；若未来退库存返售需单独演进规则。无真实第三方退款。

## 3. V12-C活动与库存表（`sql/flowgate-v12-c.sql`拟议）

| 表 | 必需字段/约束 | 作用 |
|---|---|---|
| `fg_promotion` | id PK、sku_id FK、offer_price_cents>=0、begin_at、end_at、status DRAFT/READY/RUNNING/PAUSED/ENDED、epoch/version、per_user_limit固定1、accepted_deadline_seconds、order_pay_ttl_seconds、created_at | 管理员发布后不可变更SKU/货币/活动价/总库存规则，活动版本防Redis旧状态复活 |
| `fg_promo_stock` | activity_id PK FK、total、reserved、sold、version；`CHECK(total>=0 AND reserved>=0 AND sold>=0 AND reserved+sold<=total)` | 最终权威库存，所有变化必须条件UPDATE与持有者状态CAS在同事务 |
| `fg_promo_purchase_intent` | id PK、activity_id、principal_id、application_id、api_id、period_yyyymm、client_request_id、sku_snapshot、status ACCEPTED/ORDER_READY/EXPIRED/PAID/CLOSED、order_id NULL UNIQUE、created_at、order_creation_deadline_at | `UNIQUE(activity_id,principal_id)`防同用户多个APP重复，`INDEX(status,order_creation_deadline_at,id)`回收孤儿受理 |
| `fg_promo_redis_sync`（可选，若需要可运维证据） | activity_id PK、epoch、last_reconcile_at、last_mismatch、state READY/REBUILDING | Redis版本/重建事实也可放在fg_promotion，不额外设表除非真实场景必要 |

### 活动请求/受理/创单/付款严格状态

1. 管理员设置限量100份（示例）、UTC活动窗、SKU与活动价、限购1。正式发布前确认Gateway速率策略、Portal/业务Worker、Topic、Outbox、MySQL索引和Redis活动epoch均真实Ready；旧V11业务异步消费者不能被新Topic吞掉。
2. `POST /portal/api/promotions/{id}/claim`先验证Portal用户与所选APP属于本人，Lua使用Redis TIME及同slot活动epoch库存与user资格；若没抢到则明确`SOLD_OUT`/`LIMIT_REACHED`；Lua赢只生成临时`claimId`，**不是已售、不是持久Intent、更不是订单**。
3. Control锁APP→slot占用HELD(holder=PROMO,holderId=intentId)→条件`UPDATE stock SET reserved=reserved+1 WHERE reserved+sold<total`→插`fg_promo_purchase_intent(status=ACCEPTED)`及业务Outbox，在同一MySQL事务提交。必须设计intent主键的分配/锁序（允许先生成唯一ID，再统一受理事务）；提交后才返回**HTTP202 +真实intentId**。任何一个步骤失败整体回滚；Lua临时候选按带epoch/claimId的CAS补偿或租约到期重建，不能假称DB已创建。MySQL独立条件/唯一约束是最后防超卖屏障。
4. `PROMO_INTENT_ACCEPTED`异步Consumer重读持久intent，验证仍为ACCEPTED且未超过`order_creation_deadline_at`，以`promo_intent_id UNIQUE`幂等插`PENDING_PAYMENT`订单、转`ORDER_READY`并记录转换；不再次加reserved。**202后Kafka长时间离线也不会隐式生成成功订单**。
5. **解决孤儿持久受理的新门槛**：必须有按`order_creation_deadline_at`扫描的`IntentTimeoutCloser`，对仍为ACCEPTED且未创单的Intent使用同一锁序/状态CAS转`EXPIRED`，在**同一MySQL事务**归还reserved、释放仅其holder的HELD槽、写`STOCK_RELEASED` Outbox；晚到Consumer看到EXPIRED后不可创建订单。否则Kafka永久不可用时100份库存可能永久卡在reserved。
6. ORDER_READY订单进入B相同`simulate-pay`/timeout/CAS流程：支付确认时先确定持有者与唯一订单，`reserved--,sold++`同事务，账务再经业务Outbox异步授权；取消/过期`reserved--`同事务，释放slot，产生幂等Redis库存修复事件；重复处理不能第二次动库存。
7. 202响应丢失后用户应可调用`GET /portal/api/promotions/{id}/my-claim`（服务端以本人principal+activity查唯一Intent，或返回无持久受理），或凭同一clientRequestId重试，返回原意图/订单事实；**仅有Redis临时claim不能回答“已受理”**。
8. 管理态`GET /admin/promotions/{id}/reconciliation`：真实total/reserved/sold、ACCEPTED/ORDER_READY/PAID/EXPIRED计数、当前活动Redis软预扣、缺订单Intent、待投递Outbox、Consumer lag；不得将`total-reserved-sold`直接当Redis“立即可抢”事实或把消费者已ACK当权益已可用。
9. Redis崩溃/持久化回退：活动切`REBUILDING`并拒绝新抢→按DB权威reserved/sold和仍有效Redis临时claim租约重新构造带新epoch的软库存→Lua单步发布新epoch→重新放行。旧事件/旧epoch补偿不得把库存又加一次。可有暂时少卖，但必须能对账、恢复、不超卖。
10. 不得在收到Lua成功后直接调用Kafka`send`并返回200“抢购成功”，不得要求用户自行检查数据库/后台来确认受理。

### C订单/意图/库存对账不变量

- `0 <= reserved + sold <= total`对全部时刻（以已提交事务快照为准）；已ACCEPTED且未终态的Intent/订单必须能解释每份reserved，每个PAID只能解释1份sold，不能出现负数或重复释放。
- 一个`activity+principal`至多一份持久Intent，一个`promo_intent_id`至多一个订单；同一APP/API/月最多一个BASE_SLOT；两个账号同一活动正常分别竞争库存。
- 活动受理可能异步到`EXPIRED`，不等于用户已购买；返回202只是**持久受理等待异步处理**。如果超时未生成订单，页面必须明确“本次受理未形成订单，库存已释放”，可附后续重试规则但**一人一场限购身份与是否允许再抢需重新裁定**。首版建议`EXPIRED`后禁止自动重复抢同场，避免历史重放歧义；补偿类人工重试必须审计。
- 订单付款成功不是权益立刻生效；只有付费收据、权益Grant/余额事务提交后才可显示FULFILLED。已使用权益订单不支持随意撤销额度/退售库存。

## 4. B/C拟议REST合同（每条均服务端验证owner和角色）

| 接口 | 主要字段/语义 | 并发/权限/错误 |
|---|---|---|
| `POST /portal/api/orders` | `{applicationId,skuId,periodUtc,clientRequestId}`；后端报价与BASE slot，返回201订单号/PENDING_PAYMENT/截止时间 | owner/sku检查，重复原单200，换参数409，已有权益/HELD409 |
| `GET /portal/api/orders?afterId&limit`、`GET /portal/api/orders/{id}` | 真实本人列表及支付/权益分离状态、快照金额、可付截止 | 跨租户404、稳定游标/限定字段，不返回支付模拟内部密钥 |
| `POST /portal/api/orders/{id}/simulate-pay` | 幂等确认，只有原购买人可点；成功只返回PAID_PENDING_FULFILLMENT或已有Fulfilled | 支付过期/关闭409；同一支付只一份PaymentRecord+Outbox |
| `POST /portal/api/orders/{id}/cancel` | 未付款可取消 | 与simulate-pay/Cron同一订单CAS，已付409 |
| `POST /portal/api/orders/{id}/simulate-refund`（B4可选） | 未开通或完全未使用的受控模拟退款 | 不动历史Ledger；用过必409；有审计 |
| `GET /portal/api/promotions`与`/{id}` | 真实活动窗与报价、展示soft库存不是权威 | 未上线不展示假特价 |
| `POST /portal/api/promotions/{id}/claim` | `{applicationId,clientRequestId}`→持久202 intentId | Lua预筛后DB持久受理；Redis失败503/限流429、DB失败无202 |
| `GET /portal/api/promotions/{id}/my-claim`与`GET /portal/api/purchase-intents/{id}` | 本人可靠Intent状态和订单链接 | 响应丢失可找回；跨租户404 |
| `POST /admin/promotions`、`PATCH /admin/promotions/{id}`、`POST .../publish` | ADMIN按版本/活动窗发布并创建持久库存 | 活动发布后冻结SKU/规则；弱Ready/旧节点不放行 |
| `GET /admin/orders`、`GET /admin/commerce/reconciliation` | 管理员运营和异常处置，明确已付款未权益 | OPERATOR/VIEWER只读，补偿/重排ADMIN且审计 |

## 5. 测试与验收的硬线（B/C执行时逐阶段编成Codex任务，当前未运行）

**B应验证**：两Controller并发同IDempotencyKey，价格篡改，应用归属，商品下架竞态，订单创建前Slot HELD，同月已有免费BASE拦截付费，超时与支付争锁，收据唯一，Kafka停恢复，业务Outbox SENT丢消费重投，重复消息100次一份Grant，提现/真实支付绝不提供；已付未开通页面明确待处理。原V11 Free5、旧C日报独立保留。模拟退款若启用还需“零使用vs已使用”、“退款vsWorker交叉”、“活动sold不自动返售”。

**C应验证**：从不返回“Redis赢=买到”；两Controller/多Worker/100份/目标逐步压到2万并发真实候选（实验要求，不是已达到数据）；Redis Lua→DB受理中断、受理后Kafka离线、支付vs关闭、Intent孤儿过期、消费乱序/重复、故障恢复、slot与库存核账、Redis epoch失忆重建与带错epoch的重复补偿、用户丢失202响应后按`my-claim`回查；旧V11/新V12 API实际授权且不会双扣。

**不应增加**：真实银行卡/支付宝渠道、优惠券叠加引擎、ShardingSphere、跨Redis/MySQL/Kafka分布式事务框架或宣称Exactly-Once。只要求**最终一致+可恢复事实+清晰可审计状态**，压力和延迟由隔离实测报告决定。
