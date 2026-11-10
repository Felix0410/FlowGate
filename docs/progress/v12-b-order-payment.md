# FlowGate V12-B｜订单与模拟支付阶段实施合同（含A三项P1收口）

> **状态：设计完成，Codex未执行V12-B，所有实现/验收待办。** 2026-11-10从真实远端`feat/v12-a-portal@f2026c59c7a7359e14e3ecd1d10f0c1a84cac8d8`建立`docs/v12-b-order-design`。完整业务裁决见[订单架构](../design/v12-b-order-architecture.md)，Kafka→RabbitMQ优先级见[消息适配](../design/v12-rabbitmq-adaptation.md)和ADR-13，A三个P1来源见[A静态复审](../reviews/v12-a-rabbitmq-static-review-2026-11-10.md)。本合同负责**在B0真实收掉P1后**完成正常商品订单/模拟付款/异步权益/有限模拟退款，**不开发V12-C抢购或优惠券**。
>
> **拟议分支**：`feat/v12-a-portal` → `docs/v12-b-order-design`（只含设计）→ 交给本地Codex新建`feat/v12-b-orders`（B0起实际编码）；新实现PR base=设计分支且保持Draft。不要改PR#15的base、不能让B业务代码反向进入A review分支。交付必须能区分A功能已测、B0修复已测、B1～B8订单真正实测。

## B0｜合并A的P1收口，而非开一个独立A补丁项目

**B0-0：可重复安全基线**。远端HEAD、未提交变更、JDK17、MariaDB误用禁止（实际MySQL8.4）、Redis7、Rabbit3.13.7、旧V11 Kafka，记录Java/Node/原A证据的真实性和本轮NOT_EXECUTED。不要拿旧卷直接重新迁移。局部旧V11主链路可运行回归，记录有无变化。

**B0-1：错误分类有界重试，P1-A01**（改A现有代码）
- `flowgate-control/.../portal/ApiUsageGrantedEvent.java`：解析坏JSON/Jackson3异常、字段多/缺、类型错误、版本错误、日期不合法要转换为确定性永久消息错误；解析不能泄露原文。
- `.../portal/ApiUsageWorker.java`、`ApiUsageService.java`：Consumer区分(1)永久格式/权威冲突→`basicReject(false)`入Poison；(2)确定性短暂DB不可用→按新副本publisher-confirm成功且无return后才ACK旧消息；(3)不可分类异常不能无限5秒重试，有界重试/持久隔离/告警；Publisher永久坏Outbox设置可查询的`last_error_code`并排除自动claim（可用现有FAILED与错误码过滤，无需强行迁移A表CHECK）；Broker暂不可用仍有界退避。
- 重试计数必须作为明确的不可随意信任的消息头及消费者校验（如`x-flowgate-retry-count`），每个副本带原eventId，累计上限建议**12次**，达到上限后隔离和运营处理。实现中要确保Rabbit自动DLX与应用retry header相互作用不会绕过限制。

**B0-2：真实Rabbit故障证据，P1-A02**（只碰新隔离容器）
- 官方Rabbit镜像版本/digest固定在隔离Compose；真实输出Broker版本、quorum主/Retry/Poison队列属性、DLX`at-least-once`+overflow`reject-publish`与`stream_queue`特性启用状态；单Broker只证明持卷恢复不冒充多节点HA。
- 实测新Ledger/Outbox事务后Broker不可用→Outbox保留待投→Broker重启且保留自有卷后补投→Consumed/Daily追平且不双计。
- 实测语法坏JSON/字段权威冲突到Poison、TTL retry真正路由再消费、MySQL短断恢复与原消息ACK顺序、publisher NACK或mandatory return下Outbox不能误写SENT。
- 每个实验记录隔离owner、Rabbit真实版本、事件量、队列计数、DB前后计数/退出码与失败尝试；保存`docs/progress/evidence/v12-b0-rabbit-fixes.json`。**不能拿4个Mockito测试替代真实故障**。

**B0-3：旧V11 OFF新市场安全空态，P1-A03**（改A管理页面/新API）
- `.../portal/MarketAdminController.java`、`MarketCatalogService.java`和`static/console/market.js`：只读无新表依赖`/admin/market/readiness`，状态`DISABLED/SCHEMA_NOT_READY/READY`；旧V11无V12表+新开关OFF时不直接SELECT新市场表、不500，原管理功能/权限照常；ADMIN创建/上架也拒绝而非自动装表。
- 真正独立旧V11库（不装A表）和V12-A全新库两组HTTP+Node检查；A原Portal真业务不可退化，安全开关/缺表时不发布付费入口。
- 提交`fix(v12-b): close inherited A Rabbit and legacy-console P1 issues`、定向证据与中文指南。**B0失败停止，不进入B1**。

## B1｜新数据库与可靠合同模块

**迁移**：`sql/flowgate-v12-b.sql`只新增`fg_order`、`fg_payment_record`、`fg_business_outbox`、`fg_business_event_consumed`、`fg_order_transition`、`fg_simulated_refund`六表；数量、字段、金额范围、两个唯一付款约束、Order请求唯一键、业务Outbox唯一聚合版本、订单状态CHECK、索引及FK以架构主文为准。必须在全新隔离MySQL8.4执行`V1→V11→C→A→B`，重复再执行2次、检查索引/外键/CHECK、无DROP/TRUNCATE/旧余额变动；原未迁移库所有B只读就绪API不500。

**建议代码**：
- `flowgate-control/src/main/java/com/flowgate/control/commerce/OrderStatus.java`
- `.../commerce/OrderStore.java`或`OrderMapper.java`（固定SQL参数化，避免前端传SQL）
- `.../commerce/CommerceReadiness.java`、`OrderFingerprint.java`
- `.../commerce/CommerceError.java`（复用现有ApiFailure格式，避免二套异常规范）
- `.../commerce/OrderSnapshot.java`/轻量DTO。

**硬规则**：请求端priceCents/units/period/userId不被允许，金额单位分（`long`，不能浮点）；订单SKU报价/单位/期间来源DB锁定快照，历史不更新。B只允许`source NORMAL`、`sku.kind PAID_BASE`且`price_cents>0`。数据迁移不安装促销C表。

**测试**：DDL正反约束、旧Free5与新订单各自可用、同幂等键不同参数唯一409、同APP/API/月BASE槽只能一个HELD或GRANTED。

## B2｜真实付费SKU及管理Console/Portal商品页

**建议代码**：
- 扩展`.../portal/MarketCatalogService.java`、`MarketAdminController.java`而不是再造一套无ACL商品表。
- `flowgate-control/src/main/resources/static/console/market.js`更新SKU创建：0元免费与正价PAID_BASE、CURRENT_MONTH/NEXT_MONTH；已引用SKU不可修改报价或单位，展示业务真实有效期。
- `flowgate-control/src/main/resources/static/portal/app.js`商品详情支持真实PAID_BASE报价、自己APP选择与一键“确认订单”；0元仍走免费领用流程，不能显示未实现C活动/支付成功演示按钮。

**API**：
- `POST /admin/market/listings/{id}/skus` ADMIN only：正价SKU，服务端强校验kind/price/period，不允许免费请求提正价。
- `GET /portal/api/catalog/apis/{id}/offers`：FREE与PAID全部真实已上线；返回售卖资格/不可售原因，不伪造库存或折扣。
- Portal普通用户不输入`applicationId`的数值文本框，而是从“我的应用”下拉选择，自有开发者/APP所有权服务端仍验证。

**测试**：商品下架/类型/报价边界、SKU不可变、零元不走付费、Portal DOM 真实价格分转元仅作为展示、原免费领取不受影响。

## B3｜普通创建订单与BASE槽预占

**建议代码**：
- `.../commerce/OrderApplicationService.java`：`create(user,skuId,appId,clientRequestId)`，`list`和`detail`；
- `.../commerce/OrderTransactionService.java`：复用A`PortalOwnershipService`、`MarketCatalogService`、`V12Readiness`、`PortalStore`，用TransactionTemplate控制事务；避免前端直接调用ADMIN接口；
- `.../portal/PortalOrderController.java`：`POST /portal/api/orders`、`GET /portal/api/orders`与`GET /portal/api/orders/{orderNo}`；
- `.../commerce/BaseEntitlementSlotService.java`：收敛A的锁槽语义，不能给旧免费领取引入回归。

**SQL并发最小核心**：
1. 验证当前登录主体、OWNER、APP ACTIVE；同`(user,requestId)`查询已存订单，`fingerprint=SHA256(userId,appId,skuId,skuKind,periodPolicy)`同参返回原单，无参冲突409。因为sku.period冻结在DB，指纹应从**请求确定性字段**构造，不能把随UTC月变化的字段放入同一clientRequestId重试指纹，才可跨月返回同单。
2. 锁APP`FOR UPDATE`→检查并插空slot→slot当前读`FOR UPDATE`→如HELD/GRANTED返回409`BASE_ENTITLEMENT_CONFLICT`→冻结CURRENT/NEXT月份、price/units/有效期→插`fg_order(PENDING_PAYMENT)`→slot`HELD(holder_type=ORDER,holder_id=orderId)`→`fg_order_transition`同一事务提交，HTTP201。
3. 唯一键并发冲突需让失败事务先回滚再按同user+requestId重新读已胜出的订单，**不要把DB duplicate-key直接当未知500**；重试POST返回原订单且状态可能已付款/取消。按SQL`UNIQUE(principal_id,client_request_id)`安全处理不同APP并发。
4. 仅内部用户owner可查单，输入任意别人的orderNo/应用id一律404；Order快照的`price_cents,period,api_id,app_id`后续不能随SKU/市场变更。

**测试**：同用户20路重复创建一单/不同商品同key409、两Control并发，同APP/API/月竞争不同买家或FREE vs PAID（都不可双持）、换应用仍不绕过免费领取终身一次。

## B4｜模拟支付与关单竞态、MySQL Outbox

**建议代码**：
- `.../commerce/SimulatedPaymentService.java`、`OrderTimeoutCloser.java`、`OrderTransitionService.java`；
- `.../portal/PortalOrderController.java`加`simulate-pay`和`cancel`，所有业务在原同一个MySQL中，不调用外部支付。
- 有界`@Scheduled`按`(status,pay_deadline_at,id)`批量扫描待关订单，单次不超20行；**不能在全表扫描事务上先锁订单与APP而反转锁序**：先无锁取候选ID/immutable字段，再按APP→slot→order复核过期条件与CAS；关闭事件不必再发Rabbit，事务内transition足够可解释。

**支付事务（同MySQL）**：
1. 非锁定只读order定位APP/API/月→`fg_application FOR UPDATE`→slot FOR UPDATE→Order FOR UPDATE；验证同本order的HELD与未过期`PENDING_PAYMENT`、原用户、已冻结金额且付款收据不存在；
2. `UPDATE fg_order SET status='PAID_PENDING_FULFILLMENT',version=version+1 ... WHERE status='PENDING_PAYMENT' AND pay_deadline_at>UTC_TIMESTAMP(3)`，唯一`fg_payment_record(provider SIM)`、追加Transition和`fg_business_outbox(PAYMENT_CONFIRMED,eventVersion=1)`同事务，提交后才HTTP200；
3. cancel/timeout同样锁序，只从PENDING_PAYMENT转CANCELLED/CLOSED_TIMEOUT，释放**仅此订单持有**的HELD slot并留Transition；模拟支付和关单只能一个事务赢。过期不可支付，即使浏览器按钮仍可见。
4. 跨月支付截止仍采用创建时冻结的period，CURRENT需≥15分钟保护窗，付款期限不得晚于`monthEnd-5min`；NEXT月付款后权益下月起生效。

**测试**：真实2/20线程支付 vs 超时关闭、不同Control、重复支付100次收据仍唯一且事件唯一、超时任务双实例不二次释放、边界DBUTC月末、用户手改金额拒绝。

## B5｜独立Rabbit Commerce投递、可靠权益开通与运营补投

**建议代码**：
- `.../commerce/CommerceRabbitConfiguration.java`、`BusinessOutboxPublisher.java`、`CommerceEventCodec.java`、`OrderFulfillmentWorker.java`、`CommerceOperationsService.java`、`CommerceReconciliationService.java`。
- `flowgate.commerce.v12` DirectExchange、`flowgate.commerce.v12.orders` quorum、`flowgate.commerce.v12.retry` TTL、`flowgate.commerce.v12.poison` quorum，固定V12单Broker上**独立于A usage的监听线程/Outbox/消费表**；不能接旧V11 Kafka事件、不要复用旧C严格八字段解析。
- 新拓扑要使用**B0验证后修正的**Jackson永久错误分类、Rabbit confirm/return、自动有界重试、手动ACK、毒消息保留和实例启动冷却机制；`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED=false`默认关闭，关闭不建立业务交换机/队列/线程，既有A/旧C保持正常。

**消费事务**：
1. 严格解析`PAYMENT_CONFIRMED`版本+原eventId/orderId，核对本地业务Outbox；先非锁定读Order immutable fields定位，拿APP→slot→order锁；
2. 若Order=PAID_PENDING/RETRY且槽holder为本order并且期间尚可开通，校验模拟付款收据，使用现有`fg_entitlement_grant(source_type='PAID_ORDER',source_id=orderId)`唯一建立Grant+本APP/API/month Subscription/Balance、槽GRANTED、Order FULFILLED、transition、BusinessConsumedEvent同事务；成功COMMIT后才basicAck。
3. 如Order已经FULFILLED与对应同一Grant，归类`ALREADY_APPLIED`且消费去重，不重插/补额度；如CANCELLED/CLOSED/REFUNDED且事件确属历史支付，归类`IGNORED_TERMINAL`，不复活槽或状态；永久事件与DB权威冲突入Poison并提供ADMIN修复路径，不能ACK后无痕丢弃。
4. Rabbit Broker停机或消费DB短断，Outbox仍NEW/FAILED/SENT（按真状态），付款事实保留；用户见`PAID_PENDING_FULFILLMENT`或RETRY。SENT未消费经过**300秒+本进程启动300秒冷却**、ADMIN审计与CAS重新排队**原eventId**，不二次收款；多ADMIN竞争只一个能重排。
5. 处理月末过期且已付款但未开通的Order→`FULFILLMENT_REVIEW`（留HELD待运营受控模拟退款），不可偷偷挪到NEXT_MONTH；运营Console明确显示已付款、未履约及故障代码。对账必须能发现孤儿Grant/Consumed、单订单双收据或HELD无人认领，日报今日不假MATCH。

**真实故障最小测试**：暂停Rabbit/重启保留本任务卷、消费者提交成功后阻断ACK、publisher confirm超时/mandatory return、重复投递100次、错误JSON/权威冲突Poison、DB短断自动恢复、已付款未开通退款竞态、真2个消费线程只开一份Grant。

## B6｜受控模拟退款、订单/运营页面与中文指南

**模拟退款服务**：`.../commerce/SimulatedRefundService.java`，`POST /portal/api/orders/{orderNo}/simulate-refund`不接真实金流。
- PAID_PENDING/RETRY/REVIEW且尚未开通：持APP→slot→order；退款收据（`UNIQUE(order_id)`）与Order REFUNDED、只释放本order持有的HELD、Transition同事务；晚到Worker看到REFUNDED只能IGNORED_TERMINAL。
- FULFILLED且完全没有授权过：持APP→slot→order→sub/balance锁；`remaining==total`且该APP/API/month对应订阅没有**任何**Ledger才将subscription置SUSPENDED，Order REFUNDED，Slot仍GRANTED，绝不返售当前月BASE；一个扣额曾发生就409`USED_ENTITLEMENT_NON_REFUNDABLE`。
- 重复退款返回原模拟收据而非重复插。退款和Worker/配额授权真实并发需要证明不会“已退但还能用”或“一笔钱开两份权益”。
- B只做以上明确安全范围，不做跨月现金退款、优惠券、已使用额度逆向撤销或活动库存回补。

**Portal页面**（现有`static/portal/app.js`组件化按实际需要拆，不引入Vite/React）：市场付费SKU/金额、选择本人APP、确认下单、订单列表/订单详情、模拟支付/取消/退款、处理中与异常、下月生效说明、订单和权益/Key/使用量互相跳转。所有订单操作都用普通用户能理解的商品名/应用名，**不能让用户填写数据库ID/JSON/策略ID**。显式文案“模拟支付”“已付款待开通”“已开通下月生效”“退款仅模拟且未使用可退”。

**Console页面**（已有七域工作台导航）：在“客户业务”或“API市场”下组织**订单运营**，不为了功能再造一级口号菜单；筛选待支付/已付未履约/已开通/已关闭/已模拟退款，查看唯一收据、业务Rabbit失败/补投与只读对账；所有写动作ADMIN服务端权限，读权按现有ACL。不要假热榜/假销量/固定成功率。

**中文帮助**：`docs/guides/v12-b-orders.md`、`docs/guides/v12-a-portal.md`新增用户如何下单/状态含义、`docs/guides/product-console-guide.md`同步运营订单处理/失败与RabbitMQ诊断；有非破坏的独立本地启动与复原步骤。每个后端子段同提交更新对应Portal/Console空态与帮助，不能最后才集中做半成品界面。

## B7｜核心回归与隔离综合验收

**脚本建议**：`scripts/v12-b-smoke.py`、`scripts/v12-b-orders-test.mjs`、`scripts/v12-b-browser.py`或当前真实浏览器能力；无需另搭新测试框架。所有脚本仅初始化新的**自有随机Compose/随机MySQL/Redis/Kafka/Rabbit卷**，`stop`留卷、不更改用户.env、不占用其IDE端口。完全继承A的Quota授权与Portal会话。

| 证据组 | 最低门槛 | 不能冒充 |
|---|---|---|
| B0-P1收口 | 真Rabbit毒消息/TTL/重建/恢复/Confirm；无V12表旧Console 200+明确DISABLED | 只写Mock/Fake或把P1标记修复 |
| Schema+权限 | B SQL重复迁移与CHECK/UNIQUE/FK、Portal双账号、ADMIN/OPERATOR/VIEWER/APP角色用途 | 测试单一ADMIN会话等于租户隔离 |
| 订单幂等 | 相同UUID重复请求同订单、换SKU409、双Control与APP月槽冲突、报价防篡改 | Redis SETNX代替MySQL唯一 |
| 模拟支付/关单 | 重复付款100次仅一收据，支付vs关闭互斥，关单后不再支付/开通 | 单线程方法调用 |
| Rabbit异步开通 | Broker断开已付款仍持久待履约；恢复最终1 Grant；重复100次1 grant；ACK前中断不丢 | Rabbit在线1条日志或只查队列空 |
| 退款/权限/Quota | 未用可以模拟退款、已使用拒绝；退款vsWorker/Quota；下月有效期不提前授权，旧V11额度/日报不受扰 | UI隐藏退款按钮替代DB权限 |
| 产品体验 | 普通用户1280/375真实页面“选SKU/应用→下单→模拟付→权益到达→Gateway→账本”；运营可处理异常 | DOM模拟替代真人体验 |
| 回归 | Java17模块/关键旧V11 B23/C26、A实际Free5、Node Portal/Console、Rabbit A usage和B commerce互不干扰，真实exitCode | 直接沿用A的82项历史成绩 |

每次证据保存到**新文件**`docs/progress/evidence/v12-b0-*.json`、`v12-b-*.json`及浏览器真实截图，保留A所有历史证据；记录提交SHA、环境端口、MySQL余额前后、ledger/grant/slot/order/payment/outbox/consumer数量、Broker版本、失败与重试过程，脱敏真实用户口令和Token，未执行列NOT_EXECUTED。

## B8｜复审、手动验收与停机线

- ChatGPT重新审计PR真实远端源码并比对B0三项P1已经**实际改代码+补真实证据**，再检查B1～B7事务/状态/权限和Rabbit新队列是否做到；不把本轮设计视作修复完成。
- 负责人真人按`docs/guides/v12-b-orders.md`体验正常订单、异步已付未开通、模拟退款限制、管理员订单和Portal普通用户1270/375（以1280/375为准）、键盘与错误恢复；如人测暂不做必须保留NOT_EXECUTED，不强制因此大规模重做B业务。
- V11 E6真人验收与V11 D 10万SQL/四组公平性能仍为独立历史待办，不把它们算作B本阶段真实成绩。
- 旧A的P1后修若在B0已通过则需在B的证据标注`resolved_in_v12_b0`，但**不能不经负责人确认去改旧A PR #15为“已通过”**；新Draft PR最终链路要保留回溯。
- 实施结束仅在`feat/v12-b-orders`提交Conventional Commits与Draft PR，base `docs/v12-b-order-design`；不得合并main、修改A/旧PR base、强推、清理旧卷、进入C抢购或D性能。

## 进度表（设计阶段初始化；任何“完成”必须有真实SHA/测试）

| 段 | 内容 | 真实编码状态 | 计划证据 |
|---|---|---|---|
| B0 | A三项P1修复+真Rabbit故障+旧Console OFF | NOT_STARTED | `v12-b0-rabbit-fixes.json`、`v12-b0-console.json` |
| B1 | 6表新迁移/约束/就绪门禁 | NOT_STARTED | `v12-b1-schema.json` |
| B2 | PAID_BASE SKU/市场页面与上架 | NOT_STARTED | `v12-b2-market.json` |
| B3 | 幂等订单与BASE槽预占 | NOT_STARTED | `v12-b3-order.json` |
| B4 | 模拟付款/超时关闭竞态/唯一收据/Outbox | NOT_STARTED | `v12-b4-payment.json` |
| B5 | Rabbit Commerce/可靠权益/消费幂等/补投对账 | NOT_STARTED | `v12-b5-commerce-rabbit.json` |
| B6 | 受控模拟退款、Portal/Console/中文帮助 | NOT_STARTED | `v12-b6-experience.json` |
| B7 | 全链路、定向真实故障、回归与浏览器 | NOT_STARTED | `v12-b7-e2e.json` |
| B8 | 远端源码复审/负责人真人手测 | NOT_STARTED | `docs/reviews/v12-b-*.md` |

**最终交付需能手动演示**：普通用户A注册/APP→B购买正价本月套餐→MySQL订单→模拟付款→Rabbit异步Grant→同一个APP Token经Gateway扣该API额度→订单与余额/流水→未用权益受控模拟退款及已用拒绝→运营Console真实订单/消息恢复；同时旧V11、A免费领取不被破坏。B设计定稿不等于B实施完成。
