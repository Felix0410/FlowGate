# FlowGate V12-B｜订单事务伪代码、SQL不变量与故障验收清单

> **设计核对文档，不是实际SQL迁移或测试结果。** 这是`v12-b-order-architecture.md`的执行级补充，面向本地Codex减少业务规则临场推断；旧A三个Rabbit P1统一在B0真实整改。表名/列名以该架构表字典、最终可执行MySQL8.4 DDL为准，以下SQL为语义化伪代码而非保证直接执行的脚本。

## 1. 共用约束与返回语义

所有操作在隔离MySQL8.4 `READ COMMITTED`事务中按相同应用锁序进行；HTTP权限检查前，用户身份必须从Portal Token服务端取得。事务成功之后才返回成功或异步队列受理；异常必须彻底ROLLBACK，不发出Rabbit消息、不提前写Redis已购买标志。

- 基础身份事实：`PortalUser(status=ACTIVE)→DeveloperMember(role=OWNER,ACTIVE)→Application(status=ACTIVE)`，开发者亦ACTIVE；Server从`fg_api_sku`引用的已上市Listing及真实可信V12 API路由推导`apiId,quotaUnits,periodPolicy,priceCents`，客户端不能报价。
- 老V11兼容门槛：与A领取行为一致，同APP在同一目标UTC月若已有生效的`fg_subscription`（legacy APP月池），B首版拒绝`409 LEGACY_SUBSCRIPTION_CONFLICT`，避免同APP混用旧全局月池与新API月池带来的认知/对账歧义；旧Ledger、旧订阅和C统计仍不变。不同APP可以分别走Legacy和V12，新V12单路由不双扣。
- APP/API/UTC月的BASE槽`PRIMARY KEY(app_id,api_id,period)`，期望状态只能`AVAILABLE/HELD/GRANTED`；HELD必须持有人是当前ORDER+orderId；付费后直到Grant或明确模拟退款之前不得释放。
- 订单指纹`SHA256(qualifiedUserId,applicationId,skuId,skuKind,periodPolicy)`：应在客户端请求/不可变SKU元数据下确定；**不包含提交时NOW或动态冻结YYYYMM**，以便同clientRequestId下月重试仍返回原订单。实际以相同字段相同规范字节顺序序列化，不能拼接可能歧义的字符串。数据库UNIQUE(user,requestId)最终裁决。
- 表中金额`price_cents`用long且严格`1..99_999_999`（首版上限可在实现前统一于DDL与服务校验，不能出现负数/小数/溢出），quotaUnits与A相同`1..1_000_000`。订单期间由SKU策略`CURRENT_MONTH`或`NEXT_MONTH`唯一冻结，时间使用`UTC_TIMESTAMP(3)`。
- 当前月购买至少距月末15分钟；付款deadline=`min(createTime+15min, currentMonthEndUTC-5min)`，仅首次创建冻结。下月SKU付款期限=createTime+15min；下月权益有效期`[nextMonthStart,nextNextMonthStart)`。返回前必须核对deadline>NOW。
- 订单状态不含“Rabbit成功”字段；Rabbit是否消费由`fg_business_event_consumed`及业务状态核对，`PAID_PENDING_FULFILLMENT`并不代表FULFILLED。

## 2. POST普通订单：同一个请求永远只有一个真实结果

```text
orderCreate(user,app,sku,requestId):
  1) 仅接受请求 applicationId, skuId, clientRequestId。
     预读取不可变SKU并规范化fingerprint，按(user,requestId)查已存在order：
     SAME_FINGERPRINT -> 200原订单快照/当前状态；DIFFERENT -> 409。
  2) 在一个DB事务：LOCK fg_application FOR UPDATE；
     验证OWNER/APP/Developer ACTIVE及本月老fg_subscription不冲突；
     验证SKU为PAID_BASE、正价、LISTED、真实发布V12计量能力；
     DB UTC冻结period和买卖保护窗；
     INSERT IGNORE fg_api_entitlement_slot(APP,API,period,AVAILABLE);
     SELECT slot FOR UPDATE; state!=AVAILABLE -> 409;
     INSERT order PENDING_PAYMENT [immutable quote, period, request fingerprint, deadline];
     UPDATE slot SET HELD(holder_type='ORDER',holder_id=orderId,version++);
     INSERT order_transition(NULL -> PENDING_PAYMENT)；COMMIT。
  3) UNIQUE(user,requestId)冲突：整个事务回滚后重新查真正胜出的订单，
     fingerprint相同才返回200，否则409；不可带着未回滚的事务去重试锁槽。
  4) 有效已提交订单回201，不能因为Rabbit不可用返回“权益已经开通”。
```

**必须测试**：请求返回201时order.PENDING且slot.HELD绑定此order；另一条请求相同APP/API/month只能409；同一个用户同时对两APP使用同一个key也最多一个订单；同一次key隔月重放不能创建新月份订单或改价格。已经退款的相同key重试依旧返回历史退款订单；新单必须新key。

## 3. 付款与超时竞争同一行

```text
simulatePay(orderNo,user):
  1) 无锁读取order immutable app/api/period，只用于定位。
  2) TX锁 APP→SLOT→ORDER，重验order归属/状态/holder/price/deadline。
  3) 若之前已付款，返回已有收据/真实状态（绝不再插）；终态已关闭/已退款 -> 409。
  4) UPDATE order WHERE id=? AND status='PENDING_PAYMENT'
                           AND pay_deadline_at>UTC_TIMESTAMP(3)
                           SET status='PAID_PENDING_FULFILLMENT',version++;
     若affected!=1 -> 409或返回幂等已付款事实。
  5) INSERT fg_payment_record UNIQUE(order_id) with SIM+order price.
     INSERT fg_order_transition(PENDING -> PAID_PENDING);
     INSERT fg_business_outbox PAYMENT_CONFIRMED(immutable eventId,orderId,orderRevision)
       UNIQUE(event_type,aggregateType,aggregateId,aggregateRevision)；
     COMMIT后200 PAID_PENDING_FULFILLMENT。
timeoutCancel(orderId, mode):
  1) 无锁查immutable定位，再TX锁 APP→SLOT→ORDER，重验用户/状态。
  2) 仅PENDING，超时要求DB NOW>=deadline，用户取消不需要过期；
     UPDATE order WHERE status='PENDING_PAYMENT' [deadline predicate when timeout];
     UPDATE slot WHERE holder=ORDER/orderId AND state=HELD SET AVAILABLE NULL holder;
     INSERT transition；COMMIT。
```

**必须测试**：付款线程1/超时线程1两个事务真实争锁，胜者只一个；输了的分支不释放新的order持有的slot；Cron两Control重复触发只关闭一次。重复支付100次仅一`fg_payment_record`和唯一`PAYMENT_CONFIRMED`Outbox；未知Rabbit状态不会把订单退回PENDING。今日UTC月末前后支付按固定DB时间可控测试，不能测试依赖本机时区。

## 4. Rabbit消费者处理PAYMENT_CONFIRMED

```text
consume(event):
  1) 严格JSON验证版本、eventId/OrderId、事件类型和大小；坏JSON/未知类型
     -> Poision/人工保留，无永久5秒循环。取Outbox同eventId及payloadHash事实核验。
  2) 无锁读order定位app/api/period（只定位）；TX锁APP→slot→ORDER；
     检查PaymentRecord唯一、金额/目标月份快照、slot holder当前本order。
  3a) 若order=PAID_PENDING/RETRY且monthEnd>DB_NOW:
       INSERT fg_entitlement_grant(sourceType=PAID_ORDER,sourceId=orderId) UNIQUE;
       INSERT fg_api_subscription(app,api,period,... status ACTIVE) UNIQUE;
       INSERT fg_api_quota_balance(app,api,period,total=quotaUnits,remaining=quotaUnits) UNIQUE;
       UPDATE slot HELD->GRANTED(保留OWNER来源);
       UPDATE order -> FULFILLED;
       INSERT transition and fg_business_event_consumed(eventId,payloadHash,result=APPLIED)
       COMMIT后basicAck(tag,false).
  3b) 已经FULFILLED+同订单Grant/订阅余额均存在：
       只写幂等Consumed(result=ALREADY_APPLIED)；COMMIT->basicAck；不重置余额。
  3c) 订单REFUNDED/CANCELLED/CLOSED且消息本身确属于历史已付事实：
       只写Consumed(result=IGNORED_TERMINAL)；COMMIT->basicAck；不重占slot。
  3d) 已付款但月末已过不可再履约：
       TX更新FULFILLMENT_REVIEW并记录原因，保留订单付款收据与原slot持有人；
       管理端可见并允许受控模拟退款，不默默改下月。
  4) 真暂时DB失败 -> 将原eventId持久retry副本投递并确认后ACK原件；
     不可恢复权威冲突 -> Poison并运营告警；ACK失败可以重复投递，
     所有副作用通过DB唯一键/状态事务防双开通。
```

**一致性要求**：用户`simulate-pay`看到200时，Rabbit可能不可用，订单仍已付款且Outbox可补投；Rabbit成功ACK不等于用户一定已经消耗额度；`FULFILLED`意味着Grant+Balance已提交，但若有效期未开始仍不能Gateway调用。今天的运营对账不把未消费事件当数据完整性损坏。

## 5. 模拟退款与配额授权并发

```text
simulateRefund(order,user):
  1) 非锁定读取immutable定位APP/API/月；TX锁APP→slot→ORDER。
  2a) status IN(PAID_PENDING_FULFILLMENT,FULFILLMENT_RETRY,FULFILLMENT_REVIEW)
      且无本单Grant:
      INSERT refund UNIQUE(order_id); ORDER->REFUNDED;
      if slot still HELD by ORDER/orderId: release AVAILABLE; INSERT transition; COMMIT.
  2b) status=FULFILLED:
      LOCK fg_api_subscription/fg_api_quota_balance;
      确定本订阅剩余==总量，且NOT EXISTS任何相关fg_api_quota_ledger：
      INSERT refund; subscription.status=SUSPENDED;
      ORDER->REFUNDED; SLOT仍GRANTED(保留历史来源);
      INSERT transition；COMMIT。
  2c) 若已有消耗记录/月底后，按契约判断是否可退，
      已使用返回409 USED_ENTITLEMENT_NON_REFUNDABLE；
      已退款返回原refund记录（幂等），未付款不能走退款，应该取消。
```

**必须测试**：已付款未开通→退款→迟到消费者只记录忽略、Slot可让后来的另一个订单占用而不会被旧Consumer修改；FULFILLED且未用→SUSPENDED且Gateway403；FULFILLED已用→409且Ledger/余额完全不动；同一瞬间Gateway扣额和退款两个Control竞争APP锁，观察结果只可能先扣量然后退款被拒绝，或先退款成功然后Gateway授权拒绝，不能同时双赢。

## 6. Rabbit和旧环境基础设施最小故障矩阵（B0后B5重用）

| 注入位置 | 期望事实 | 本阶段证据 |
|---|---|---|
| V12-A usage JSON不是合法对象 | 进入`flowgate.usage.v12.poison`，不每5s重投 | Rabbit实际主/毒队列计数与errorCode |
| A Outbox本地事件非法 | 无法发布但不无限FAILED自动重领，给运营可修复状态 | 错误码、下轮claim排除 |
| A usage MySQL短不可用 | 确认retry副本后ACK原件；恢复日报只记一次 | TTL retry实际路由/恢复消费 |
| 旧V11库无V12 A表、默认OFF | Console市场明确NOT_READY且其它20能力仍能使用 | 两库隔离HTTP/浏览器 |
| B付款提交后Rabbit停机 | 订单PAID_PENDING+SIM收据+Outbox完整，Gateway不能凭付款授权 | Rabbit停机期间SQL及Portal |
| B Rabbit持卷重建 | 原业务事件可消费或由原Outbox恢复，Grant唯一 | 消息原eventId+前后DB |
| B消费者COMMIT后故意不ACK | Rabbit redelivery；唯一PAID_ORDER Grant，余额不重置 | 重放次数/Grant唯一 |
| B同eventId重复100次 | 一张订单、一个Grant、一份Balance、一条或一份唯一Consumed | 真实多Listener重复注入 |
| B付款vs关单/退款vsWorker | 不双扣、不释放后来Holder、不复活终态 | 多线程真实SQL状态/transition |
| V11 Kafka旧事件重放 | 旧fg_usage_daily只统计V11，V12新Rabbit表不受影响 | V11独立关键回归 |

**测试结果一律不预填**；每项自动化必须在新随机owner Compose中执行，并把事实写成脱敏JSON及真实SHA。没有执行的不能标`PASS`。生产不采用“单节点quorum就算高可用”的虚假结论；Rabbit3.13的at-least-once DLX要查`stream_queue` feature、队列实际overflow和死信转发配置，见 [Rabbit官方3.13文档](https://www.rabbitmq.com/docs/3.13/quorum-queues)。

## 7. 出厂判定（B阶段完成才允许勾选）

- 两种商品：A的免费领取仍正常、B正价SKU能上架且只由ADMIN设置；当前月和下月权益窗口行为正确。
- 两个普通用户：APP/订单/Key与额度永不串户、Token角色不能互认。
- 一单状态：PENDING_PAYMENT→SIM收据→PAID_PENDING→Rabbit异步Grant→FULFILLED→Gateway真实授权→Ledger；从付款到Grant存在合理等待文案，不冒充即时可用。
- 并发：重复创建同订单、重复支付、付款vs关闭、退款vsWorker和Gateway扣量，严格单一获胜结果。
- 异常：Rabbit断联/回放/毒消息/重启与MySQL短断后订单可恢复，Ledger/Grant与业务Consumed唯一；不存在永久“已支付但系统永远没有解释”的不可诊断状态。
- 安全：旧V11 Kafka与APP月池不修改，B新功能默认OFF、无表旧后台可用，所有既有Docker卷与用户.env/IDE不被擅自操作。
- 证据：Java17/Node/Python/真实HTTP/Chromium执行退出码、匿名业务计数与未执行清单，负责人手测状态单列，不用设计结果代替真实成绩。
