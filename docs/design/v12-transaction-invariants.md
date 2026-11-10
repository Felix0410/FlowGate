# FlowGate V1.2｜订单、库存、权益、配额与Kafka跨系统一致性合同

> **设计状态：未实现、未测试、未迁移。** 本文件是后续V12-A/B/C必须遵守的**一致性验收依据**，优先于任意Codex临时实现建议；业务主文档`flowgate-v1.2-business-architecture.md`。所有示例数量仅为不变量或待测试期望，不是现有性能结果。

## 1. 先锁定五个相互独立的事实与真相来源

| 事实 | 权威数据 | 只作缓存/异步用途 | 严格禁止 |
|---|---|---|---|
| 订单是否存在/状态/多少钱 | MySQL`fg_order`、`fg_payment_record`和订单SKU快照 | Portal状态显示、Kafka订单通知 | Redis成功就显示订单已创建或已付款 |
| 活动有多少可卖 | MySQL`fg_promo_stock.total/reserved/sold`和`fg_promo_purchase_intent` | Redis活动窗口、预扣存量/去重资格 | Redis值=购买成功/售出总量的单一证据 |
| 用户是否有某API月权益 | MySQL`fg_api_subscription`和唯一`fg_entitlement_grant` | Portal订阅卡片、商品缓存 | 付款接口直接修改旧V11`fg_subscription`或余额 |
| 每次调用有没有获得配额 | V11原`fg_quota_ledger`或V12新`fg_api_quota_ledger`与对应余额行；单笔只能命中其一 | Redis Token/速率桶、Kafka用量、Daily读模型 | Kafka/异步Daily参与实时扣配额或退款 |
| 后续步骤是否被执行 | MySQL业务Outbox、消费去重表和事务状态 | Kafka可重复传递；Redis补偿事件 | 以一次Kafka ACK或Redis INCR宣称MySQL权益最终一致 |

## 2. 强制数据库不变量（DDL CHECK + UNIQUE + 事务复核）

1. 每个用户登录名：`UNIQUE(fg_portal_user.login_name_normalized)`，主`fg_principal`的type必须是`PORTAL_USER`；用户注册创建个人Developer及会员关系同事务，跨租户查单永远先检查后端归属。
2. 商品：`fg_api_listing.api_id UNIQUE`，SKU一经被引用的价格`price_cents`、`quota_units`、`period_policy`快照永久不变；新的价格创建新revision或新SKU，不回写历史订单。
3. 订单：`fg_order.order_no UNIQUE`、`UNIQUE(user_id,client_request_id)`；在服务端对同幂等键的applicationId/apiId/skuId/period做SHA256指纹比较，不同参数返回409，**同请求重试只返回同一订单**。
4. 支付模拟凭证：`UNIQUE(order_id,provider,provider_reference)`或Provider唯一流水，确认一次只产生一份收据；未完成支付的订单没有已激活的新权益。
5. BASE权益：`fg_api_subscription UNIQUE(application_id,api_id,period_yyyymm)`；`fg_entitlement_grant UNIQUE(source_type,source_id,api_id,period_yyyymm)`，`BASE`和`TOPUP`来源区分。首次冻结BASE单位，后续TOPUP只能增加新API池且留下追加Grant，旧V11冻结始终只读。
6. V12余额：`fg_api_quota_balance UNIQUE(application_id,api_id,period_yyyymm)`，`0<=remaining_units<=total_units`；V12 Ledger `charge_id UNIQUE`且`units>0`；事务锁余额、扣余额、插唯一Ledger和事件必须成功或全部回滚。跨模式同一chargeId/路由版本不得双计，版本元数据固定meteringMode。
7. 活动抢购：`fg_promo_purchase_intent UNIQUE(activity_id,user_id)`且活动首版限购1；`fg_promo_stock CHECK(total>=0 AND reserved>=0 AND sold>=0 AND reserved+sold<=total)`；DB库存`reserved`是持久受理未付款，`sold`是已付款销售，不允许同一个intent释放两次。绑定`UNIQUE(promo_intent_id)`订单，订单至多有一张活动库存预留。
8. Outbox：`fg_business_outbox(event_id UNIQUE,event_type,aggregate_id,version,payload,status,lease...)`独立旧C用量表，`fg_business_event_consumed(event_id PK)`与业务实体唯一键双保险；生产者不能在DB事务外先发消息当唯一事件事实。
9. 所有业务金额单位是整数**分**；应用/商品单位是整数**授权单位**。不可将两者混入同一个Ledger。UTC日期来自DB时钟，期间按`[monthStartUTC,nextMonthUTC)`；订单购买的`period_yyyymm`在创建时冻结，不能根据支付到达时刻偷偷变更。

数据库唯一约束抵御多个Control/多个Consumer并发；Redis SETNX只用于加速，不替代MySQL唯一键。

## 3. 普通订单状态机：交易确认与权益开通分离

```text
PENDING_PAYMENT -------------------------> CLOSED_TIMEOUT  (超时关闭，只能未付款)
      | 模拟支付CAS + 收据 + Outbox 同事务
      v
PAID_PENDING_FULFILLMENT ----(可靠事件/幂等Worker)----> FULFILLED
      | 反复处理失败/需人工诊断
      v
FULFILLMENT_RETRY ----(修复后同一orderId重试)-------> FULFILLED

取消: PENDING_PAYMENT -> CANCELLED
退款: 仅未使用且经过受控状态校验；首版无真资金，绝不修改既有使用Ledger。
```

**不变式**：
- `PENDING_PAYMENT`之前**不能创建付费权益**；`PAID_PENDING_FULFILLMENT`表示模拟付款已确认但**APP可能尚不能调用**。只有`FULFILLED`且当前UTC月有效、对应API路由已发布/Token有效，页面才显示权益可用。
- `simulate-pay`与过期关闭更新**相同MySQL订单行**，SQL `WHERE status='PENDING_PAYMENT' AND expires_at>UTC_TIMESTAMP`或互斥锁；关闭SQL `WHERE status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP`。同一订单这两个分支只能一个成功。模拟付款一旦成功，超时任务不得撤销它。
- 支付确认事务只写订单状态、收据与业务Outbox。权益开通Worker必须在**另一个MySQL事务**一次完成来源唯一Grant、V12订阅、余额、订单`FULFILLED`与必要审计；事务异常不得ACK业务事件。失败状态诊断不能丢掉原支付收据/事件。
- 如果权益期间当前已过期，不能擅自滚到下个月。要么在订单创建时控制支付期限早于权益到期、购买时展示日期，要么进入人工退款/补偿状态；不能假授权，也不能转成新产品。
- 同应用/同API/同UTC月已有BASE时，普通购买应在**创建订单时**提示冲突，消费时还要靠唯一键二次复验；竞态后一单失败不冒充付款成功开通。未来TOPUP/升级需要显式权益种类和审核，不自动插入第二个BASE。

## 4. 特价抢购三层状态机与严格受理条件

```text
Redis临时claim（只候选，无可靠购买受理）
       |
       | MySQL: 库存reserved+1 + 持久intent + 业务Outbox 同事务
       v
ACCEPTED (HTTP 202；仅已持久排队)
       | 业务Outbox Kafka at-least-once，订单Worker幂等
       v
ORDER_READY (MySQL已有PENDING_PAYMENT订单，仍未付款)
       |                         \
       | 用户模拟付款              \ 超时/取消（订单行CAS）
       v                           v
PAID_PENDING_FULFILLMENT        CLOSED_TIMEOUT
       |                           |
       v                           v
FULFILLED（权益可用）           DB reserved-1，Redis异步补偿视图
```

**Lua职责仅限**`Redis TIME`、活动版本、窗口、用户资格、Redis临时可用名额及claimId/TTL；脚本成功**不是可靠202**，也不是可靠库存最终事实。服务若在Lua后、DB提交前崩溃，Redis孤立预扣按TTL+活动epoch重建恢复；这笔用户**没有成功受理回执**，重试看DB是否已有intent，无记录不能冒充排队成功。

**DB受理前后**：服务根据Redis候选在同一MySQL事务条件更新`reserved`、写入`fg_promo_purchase_intent`、`fg_business_outbox`，提交后才向用户返回202及intentId。此时确实有持久资格，但仍不是付款或权益。DB唯一键冲突与故障不得让Redis的临时结果自动变订单。重复抢购返回原intent/原订单状态，不产生另一份库存预留；同用户换APP也受活动级唯一键限制。

**MySQL库存转换**：订单异步创建不再次`reserved++`，只确认已有持久intent。模拟付款同事务`reserved--,sold++`，超时关闭同事务`reserved--`，两者竞争同一订单/预留状态，**最多一个方向成功**。迟到Consumer先查intent/库存保留状态，不能“复活”取消或超时intent。已经消耗的权益不因用户模拟退款自动将sold减回可售。

**Redis补偿**：MySQL释放成功后业务Outbox写`STOCK_RELEASED`，Redis处理按`(activityId,intentId,epoch)`幂等CAS更新；新活动epoch/过期临时claim的补偿不得覆盖重建后的新库存。Redis AOF或实例故障时，活动可以短暂`REBUILDING`/拒绝新抢，按MySQL`total-reserved-sold`和临时候选有效租约重建，通过Lua状态切换再开放；不保证永远“绝不少卖”，但必须能修复**漏单/少卖**，并始终**不超卖**。把Redis与DB两个库存直接INCR/DECR而无持久intent/outbox的方案一律拒绝。

## 5. 业务消息、用量消息和数据库事务不可混淆

旧C使用`fg_outbox_event + fg_consumed_event`处理`USAGE_GRANTED`，核对旧`fg_quota_ledger`并更新`fg_usage_daily`，已拥有租约/ACK/CAS/有限重试。**不要让订单事件走这个消费者的严格8字段事件解析**。V12设计：
- 同一Kafka Broker，独立Topic `flowgate.commerce.v1`、独立ConsumerGroup `flowgate-commerce-worker-v1`，独立`fg_business_outbox`和`fg_business_event_consumed`，复用Worker通用租约投递与故障策略的**实现思想/可安全抽取的代码**，但不改旧用量事件历史事实。
- 事件类型`PROMO_INTENT_ACCEPTED`、`PAYMENT_CONFIRMED`、`STOCK_RELEASED`、`ENTITLEMENT_GRANTED`等使用固定版本`eventVersion`+eventId+aggregateId+occuredAtUtc，不带Token、手机号、全量订单JSON、任何用户密码。消费者基于本地MySQL order/intent重查可信SKU和状态，不相信Kafka payload直接发额。
- 先落MySQL订单/库存/Outbox事务→提交→异步Kafka投递；消费者重试可重复，多次送同事件，MySQL唯一grant/消费去重防重复；DB提交完成后才ACK Kafka。没有Broker ACK时Outbox保持可重试，不能直接标SENT；已经SENT却未消费、Kafka丢记录仍需与C同等级别的受控重排/对账。
- V12实时API准入仍以MySQL新月余额与Ledger为准，Gateway WebClient非阻塞调用Control。为使旧C日报能包含V12调用，新增严格`USAGE_GRANTED_V12`事件版本与消费校验分支，必须核对新`fg_api_quota_ledger`、同事务幂等更新日表；旧V11版本1始终能消费，不能让两个Consumer对同eventId双计数。
- 消息乱序：Worker处理以订单/intent行状态CAS或版本号为准，旧`PAYMENT_CONFIRMED`不会覆盖`FULFILLED/REFUNDED`，迟到`PROMO_INTENT_ACCEPTED`不能复活终态。不可恢复坏消息保留offset/隔离并提供运营处理，不静默丢弃。

## 6. 必须故障注入的最小安全矩阵

| 故障窗口/并发 | 验证动作 | 唯一可接受事实 |
|---|---|---|
| 2个并发注册同登录名 | 双Control提交 | 仅1个用户主体/个人Developer，另一个409，非跨租户共享 |
| 两个用户猜对方app/order ID | Portal直接调用GET/POST | 403或不暴露的404，业务记录不变 |
| 同一个clientRequestId重试/换商品 | 两路并发创建订单 | 同参数同订单；换参数409；金额来自服务端 |
| 模拟支付 vs 超时取消 | 真实双线程同时触发 | 仅一个终态；不双开权益、不双释放库存 |
| 付款提交成功，进程在发送Kafka前崩 | 停Worker/恢复 | Outbox可补投，最终1个Grant/1笔订单FULFILLED |
| Kafka同消息100次/ACK前崩 | 真实消费者重放 | 收据/Grant/库存恰好一次，不提前ACK |
| Redis Lua扣库存后应用崩在DB前 | 注入halt/重启 | 无持久intent则不能202；临时候选过期释放，MySQL库存未减少 |
| DB受理后Kafka离线 | 抢购真实202然后停Broker | intent和DB reserved真实存在；恢复后恰好1订单，绝不失受理 |
| 100份活动2万并发（**待实测**） | 两Control/至少两Worker | DB `sold+reserved<=100`、每用户最多1、最多100合法持久intent；拒绝数/延迟据实记录 |
| Redis缓存丢失/AOF回退 | 停Redis、恢复重建 | 新抢fail-closed，MySQL订单/库存不丢，重建后无超卖/无永久少卖 |
| 临时DB故障/长事务 | 持久intent与消费恢复 | 无“已受理但DB不存在”；恢复后账本与库存最终收敛 |
| 同月V11旧订阅与V12买API | 两类发布路由实际调用 | 旧`fg_quota_balance`不改，新API池独立；无双Ledger/跨API额度消耗 |
| Kafka C旧V1事件与V12新事件混用 | 重放双方及跨天 | 两类Ledger都可核对、Daily不双计、UTC日按事件时间；当日对账不能冒充封账MATCH |
| 钱款已模拟成功但权益DAO失败 | 强制SQL约束失败→恢复 | 订单显示`PAID_PENDING_FULFILLMENT`或`FULFILLMENT_RETRY`，恢复后1个权益，不冒充已可调用 |
| 刷新Portal/退出/非法调试URL | 浏览器真实操作 | 认证及Key不会出现在URL、浏览器持久存储、请求日志，不接受任意上游URL |

**实际数值不能提前填写**。上述“2万并发/100份”是未来测试目标，不是现有V1.1测试数据。

## 7. 审核结论与取舍

**选MySQL持久受理 + Redis高并发软预扣 + Kafka订单后处理**，原因：Win者数量由库存上限控制，数据库只服务有限候选并以原子条件更新兜底；对所有请求同步写库不可行，但也不能让Redis孤立预扣充当不可恢复的“已受理”回执。重试、最终订单/权益和库存释放都有MySQL权威事件能追溯。付出的代价是每个赢者有一次MySQL写入，且在DB高压或Red​is失联时可能短暂少卖；D阶段应实测该代价，而不是假定无限QPS。

**验收语义唯一**：持久202只代表抢购受理、ORDER_READY只代表待支付、PAID_PENDING_FULFILLMENT只代表模拟付款已确认、FULFILLED才表示月权益在DB开通、Gateway授权成功才是一次真实配额准入。任何页面和监控不得把这些状态压缩为一个“抢购成功”。
