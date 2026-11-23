# V12-C｜高并发抢购MySQL事务、状态机、增量DDL与故障合同

> 只设计，未实施。真实基线：`feat/v12-m-rabbitmq-only@d6c1f33371690071c4bb7199dc9e7db5598d8079`。阅读[总体设计](v12-c-high-concurrency-promotion-architecture.md)后按此合同编码。现有B表有硬CHECK限制PROMOTION订单、B Commerce Outbox仅PAYMENT_CONFIRMED，切忌直接复用错误结构。

## 1. 数据字典（全新隔离库明确执行`sql/flowgate-v12-c.sql`）

只新增五张MySQL8.4 InnoDB表，并对现有B订单CHECK做一次**兼容且可诊断恢复的ALTER**，不删除V11/M权威账本：

| 表 | 核心字段和约束 |
|---|---|
| `fg_promotion` | id PK、sku_id/api_id FK、sku_revision、quota_units、sku_price_cents/promo_price_cents(long分)、period_yyyymm/period_policy、CNY、begins_at/ends_at、status DRAFT/READY/RUNNING/PAUSED/REBUILDING/ENDED、epoch BIGINT>0、version BIGINT>0、order_creation_ttl_seconds、pay_ttl_seconds、created_at/updated_at；原活动发布后所有报价/配额/月份/总库存不可变 |
| `fg_promo_stock` | promotion_id PK/FK、total_stock、reserved、sold、version；CHECK `reserved>=0 AND sold>=0 AND reserved+sold<=total_stock`、total 1..100000；任何库存更改均需条件UPDATE |
| `fg_promo_purchase_intent` | id PK、promotion_id/principal_id/application_id/api_id/sku_id FK、period_yyyymm、client_request_id、request_fingerprint、sku_revision、quota_units、price_cents、币种、effective_at/expires_at、status ACCEPTED/ORDER_READY/PAID/CLOSED/EXPIRED/REFUNDED、order_id NULL UNIQUE、creation_deadline_at、version、created_at/updated_at；`UNIQUE(promotion_id,principal_id)`及`UNIQUE(principal_id,client_request_id)`、`ix_intent_expiry(status,creation_deadline_at,id)` |
| `fg_promo_outbox` | id、event_id UUID UNIQUE、intent_id FK、event_type固定PROMO_INTENT_ACCEPTED、event_version=1、payload_json JSON、status NEW/IN_FLIGHT/SENT/FAILED/FAILED_PERMANENT、attempt_count、lease_owner/lease_until、next_attempt_at、last_error_code、created_at；`UNIQUE(event_type,intent_id,event_version)`+claim索引 |
| `fg_promo_event_consumed` | event_id PK FK、intent_id UNIQUE FK、payload_sha256、result APPLIED/IGNORED_TERMINAL/ALREADY_APPLIED、consumed_at；同事务写消费记录与订单创建，COMMIT才Rabbit ACK |

Intent可先插`order_id=NULL`，Worker成功插入Order后更新`order_id`，避免循环非空外键。严格核对旧B六表、旧A十四表完整和所有FK；必须有可重复两次的DDL测试，拒绝自动DROP/TRUNCATE/覆盖旧行。活动数量/价格范围和状态DB CHECK必须落实，逻辑不只是Java if。

### 旧B订单CHECK的确切处理

实际`sql/flowgate-v12-b.sql`定义了`CHECK(source='NORMAL' AND promo_intent_id IS NULL AND sku_kind='PAID_BASE')`且未显式命名。C必须扩成同时支持 `(source='NORMAL' AND promo_intent_id IS NULL) OR (source='PROMOTION' AND promo_intent_id IS NOT NULL)`，保留`sku_kind='PAID_BASE'`限制，并为非空`promo_intent_id`增加FK指向新Intent。使用`INFORMATION_SCHEMA.TABLE_CONSTRAINTS JOIN CHECK_CONSTRAINTS`匹配唯一现有CHECK，再有条件`ALTER TABLE fg_order DROP CHECK <发现名称>`/ADD命名`ck_fg_order_source_c`；**绝不猜fg_order_chk_N**。MySQL DDL不是跨多个ALTER的原子业务事务，迁移工具须在每一步校验已经完成的部分、能断点重入、不能在缺约束间隙对外提供C写流。先在隔离库演练异常/重复安装；用户常驻库要明确授权/备份。

旧普通`INSERT fg_order`默认source NORMAL和NULL仍有效；C新订单必须显式`source='PROMOTION',promo_intent_id=<intentId>`，金额/配额/有效月均从活动已冻结的Intent快照来，**不是Redis或客户HTTP**。`fg_business_outbox`继续只写PAYMENT_CONFIRMED，C意图单独用PromoOutbox；不要修改B的Payment/Grant/Used Ledger语义。

## 2. 全链路统一锁序与事务隔离

`fg_application FOR UPDATE → fg_api_entitlement_slot FOR UPDATE → fg_order FOR UPDATE（已存在时）→ fg_promo_purchase_intent FOR UPDATE → fg_promo_stock条件UPDATE`。活动行在APP/Slot之后`FOR SHARE`核验版本/status/epoch/DB UTC窗，活动REBUILDING独自`FOR UPDATE`且不锁APP或Slot，避免锁序反转。受理尚无订单，先锁Intent再Stock；Worker ACCEPTED尚无订单，锁Intent后**创建新Order**，遇已存在订单仅幂等查询，不在持Intent锁时反向锁旧Order。所有Stock行最后锁，所有正常B业务保留原路径。

采用READ_COMMITTED、8秒有界事务，数据库死锁/锁等待只在**整个事务已经回滚之后**保留同一幂等ID最多再试2次；事务内部不得重入、不得持DB锁做Redis/Rabbit网络IO。双Control抢同一APP/API/月由原Slot PK/holder CAS绝对阻止双领。

### 2.1 抢购受理 `POST /portal/api/promotions/{id}/claim`

1. 从Portal认证会话取principal，OWNER校验APP ACTIVE。先查同活动、同principal原Intent：相同`clientRequestId`+fingerprint返回原Intent与最新状态；更换APP/SKU/request参数返回409，不触发Lua二次扣软名额。即使原Intent EXPIRED仍保留限购历史。
2. Redis Lua在单个原子短脚本核对epoch、gate READY、候选用户租约、softRemaining>0，分配带token和TTL的**软候选**。没有Lua资格不代表已经真正售罄，可能悲观欠卖；返回明确SOLD_OUT/RETRY_LATER/503。
3. DB事务持APP→Slot锁，核验USER/APP、当前活动MySQL`RUNNING`、真实DB UTC时段/固定period、`epoch==Lua epoch`、上架真实API和该月B OrderStateMachine月末保护窗、既有V11同月订阅无冲突；`SELECT ... FOR SHARE`核对活动快照。先插Slot若无，再`FOR UPDATE`要求AVAILABLE；依靠唯一索引限制activity+principal及principal+requestId。
4. 同事务 `UPDATE fg_promo_stock SET reserved=reserved+1,version=version+1 WHERE promotion_id=? AND total_stock>reserved+sold` 恰一行；插`fg_promo_purchase_intent(ACCEPTED)`、Slot CAS为`HELD(holder_type=PROMO,holder_id=intent.id)`、插唯一`fg_promo_outbox(PROMO_INTENT_ACCEPTED)`，COMMIT。**只有此时HTTP202 +真实intentId**。
5. DB**确定整笔事务回滚**时通过Lua按token和epoch最多一次补偿softRemaining，补偿Redis失败只导致短期少卖不能导致多卖。遇MySQL COMMIT结果未知/网络断开时先按用户唯一Intent查询判定，仍未知就不补偿、保留软候选到重建；DB已COMMIT后HTTP丢失不补偿，用户按`my-claim`从MySQL查询恢复。持久Intent一人只能一个，新requestId也不能重新抢。

### 2.2 Rabbit意图Worker（独立quorum队列）

- 原事件固定字段：`eventVersion=1,eventId,intentId,promotionId,activityEpoch,occurredAtUtc`，消息内容严格解析/version/UUID，必须重新核验`fg_promo_outbox`记录与Intent真实主键、不可变快照，指纹不一致进入持久Poison，禁止根据MQ直接扣库存。
- `APP→Slot HELD(PROMO,intentId)→Intent FOR UPDATE`核对ACCEPTED且未过`creation_deadline_at`；唯一创建一张`fg_order(source=PROMOTION,promo_intent_id=intent.id,status=PENDING_PAYMENT,price=intent.price_cents,units=intent.units,period/effective/expires=intent快照,client_request_id=服务端保留前缀pr_<UUID>)`；普通B下单API必须拒绝客户端`pr_`前缀，防冲突。Slot holder从PROMO→ORDER同事务，Intent.ORDER_READY+order_id、OrderTransition、PromoConsumed同事务COMMIT。
- 重复/迟到：相同eventId已Consumed则重查payload指纹并返回DUPLICATE；Intent已EXPIRED/CLOSED应写IGNORED_TERMINAL且ACK，不能创建Order；若已生成唯一Order但Consumed缺失则按DB唯一外键和原eventId恢复，绝不可二次扣Stock。活动已ENDED只阻止**新的受理**，已ACCEPTED仍可在期限内创单。
- Rabbit消息Publisher必须持久+Confirm ACK且mandatory无Return才标SENT；Consumer事务COMMIT后手动ACK；临时DB错误用确认成功的独立持久TTL retry（5s），坏JSON/权威冲突及超12次未知错误进Poison。按B已有`RabbitDelivery`语义复用，但单独队列/Outbox，不把PROMO事件投给只认付款的B CommerceWorker。

### 2.3 Intent Timeout、付款、关单、退款

`PromoIntentTimeoutCloser`每次扫描<=20条未创单ACCEPTED且超时的Intent ID，先读APP再APP→Slot(PROMO)→Intent→Stock，获胜的`ACCEPTED→EXPIRED` CAS、`reserved--`与释放**原holderId**的Slot同事务。后到Worker只记录IGNORED_TERMINAL，不能复活。Rabbit长期不可用时这一Closer仍必须独立运行回收，不无限占满库存。

B `OrderService.pay()`在PROMOTION来源扩展同事务：APP→Slot(ORDER)→Order PENDING/截止校验→Intent ORDER_READY→Stock `reserved--,sold++` CAS→Order PAID_PENDING_FULFILLMENT+唯一SIM PaymentRecord+原B PAYMENT_CONFIRMED Outbox+Transition + Intent PAID COMMIT。已付重试按原单幂等，不第二次sold。对普通NORMAL完全保留原B逻辑。

B `OrderService.closeLocked()/OrderTimeoutCloser` PROMOTION未付款订单仅一方CAS赢，`reserved--`、Intent CLOSED、Slot(ORDER)释放、Order CANCELLED/CLOSED_TIMEOUT 同事务；与付款互斥；IntentCloser**只管未建单ACCEPTED**，绝不能再关ORDER_READY，否则二次释放。

B `SimulatedRefundService`对PROMOTION同事务写Intent REFUNDED但**不减少sold、不返售活动库存**；未履约退款可释放HELD，已履约完全未调用可SUSPENDED而保留GRANTED，使用过拒绝409，不修改Ledger/历史Grant，重复退款仍同一收据。V12-B CommerceService继续从原PAYMENT_CONFIRMED Outbox异步唯一Grant/订阅/余额；Intent PAID不等于权益已生效。

### 库存SQL伪代码与条件行数

```sql
-- 受理：须在状态/用户/Slot判定胜出后、同事务
UPDATE fg_promo_stock SET reserved=reserved+1,version=version+1
 WHERE promotion_id=? AND total_stock-reserved-sold>=1;

-- 仅终态CAS成功的一笔未付款关闭/Intent过期
UPDATE fg_promo_stock SET reserved=reserved-1,version=version+1
 WHERE promotion_id=? AND reserved>=1;

-- 仅模拟付款CAS成功、同一PaymentRecord/Outbox事务
UPDATE fg_promo_stock SET reserved=reserved-1,sold=sold+1,version=version+1
 WHERE promotion_id=? AND reserved>=1;
```

每条`UPDATE`返回必须=1；否则整个事务rollback。库存操作者从已锁的Order/Intent来源确定promo_id，不能让客户端传待改库存字段。

## 3. 按同一MySQL一致性快照对账

- `stock.reserved = count(intent ACCEPTED) + count(intent ORDER_READY)`，两类没有付款的有效受理各占一份；`stock.sold = count(intent PAID或REFUNDED 且关联有唯一SIM收据的Order)`。退款后sold保留表示累计成交，不是净在售；不允许订单付款时改错Intent导致“付钱但仍reserved”。
- `stock.total>=reserved+sold>=0`始终成立；`UNIQUE(activity,principal)`、`UNIQUE(promo_intent_id)`、`UNIQUE(principal,client_request_id)`分别防同用户抢多个、同Intent多单、同幂等号重放冲突。App+API+月BASE原Slot PK防跨free/B/C双开。
- 活动唯一快照与Order快照一致；Order付款收据最多1、Grant来源`PAID_ORDER/orderId`唯一、消费标记唯一。Promo Outbox SENT≠已创单，真正已创单只认Order/Intent/Consumed三者。今日样本是PROVISIONAL，未到期Intent是PENDING，缺口INTEGRITY_GAP，超出有界扫描范围UNKNOWN，不冒充最终MATCH。
- `GET /admin/promotions/{id}/reconciliation`使用只读RR事务，有限分页或样本上限1000，超过限额显示UNKNOWN/下一页，不无界扫描所有活动。禁止无授权自动修复历史权威Ledger/Payment/Stock，错误需要ADMIN审计。

## 4. 必须真实验证的故障矩阵

| 场景 | 一致性硬结果 |
|---|---|
| 两Control同时大量抢100份（20k请求为**待测目标负载**） | committed Intent<=100、Stock无超卖、任意相同principal只有1Intent、只用Redis短候选挡绝大多数DB写 |
| Lua返回候选后MySQL宕机 | 不HTTP202，Stock未加reserved；Redis补偿失败只少卖，重建后恢复 |
| DB COMMIT后HTTP丢响应 | 相同`clientRequestId`/my-claim读原Intent，仍只有1Stock预留 |
| Redis重启/FLUSHDB/NOSCRIPT | 新抢503，epoch+REBUILDING拒旧候选，新键由DB重建；无Redis回写DB库存 |
| 暂停Rabbit > Intent期限 | Closer EXPIRED并释放reserved/Slot，晚到消息IGNORED_TERMINAL |
| Worker DB commit后ACK丢失；重复100次 | 1Order/1Consumed，Slot至多持一次，库存不再变 |
| 同APP B免费领取/普通下单与C抢购竞争 | 只有一方赢Slot；C失败不生成持久202，Lua按原epoch补偿 |
| 模拟支付100次 vs Order Timeout/Cancel | 要么一张SIM收据+sold一次，要么CLOSED+reserved释放；不存在两条获胜路径 |
| 已付未开通退款 / 已用退款 | sold不返售；未用遵循B状态，已使用409、Ledger不删 |
| 活动PAUSED/ENDED或跨UTC月 | 原已ACCEPTED可按期限走下游；新的Intent被DB状态/固定period拒绝 |
| 毒消息/Redis误操作/Rabbit停止/DB暂停 | MySQL Outbox和库存可恢复，Poison可运营查看，旧V11/M及B Commerce队列隔离 |

测试证据每次新文件`docs/progress/evidence/v12-c*.json`记真实SHA、容器owner/随机端口、MySQL/Redis/Rabbit版本、状态转移/行数、HTTP状态、Broker Confirm/Retry/Poison、exitCode、失败及NOT_EXECUTED。**不是要求现在运行，也不允许生成假的PASS。**

Codex详细阶段见[实施合同](../progress/v12-c-high-concurrency.md)和[统一执行提示](../../CODEX_V12_C_EXEC_PROMPT.md)。
