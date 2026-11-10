# 交给本地Codex的唯一V12-B实施提示词（整合A阶段Rabbit P1收口）

> **文件当前是设计派工材料：Codex尚未执行；不得因本文件存在将B标为完成。** 用户要求将A的三项遗留修复**并入B**，不要再独立先执行`CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md`。先完成**B0必要修复+真实故障验证**，再B1～B7普通API付费订单，停在B8复审/真人验收。不进入C限时抢购或优惠券。

## 0. 唯一源码基线和必须阅读

- 远端已确认`feat/v12-a-portal@f2026c59c7a7359e14e3ecd1d10f0c1a84cac8d8`是已编码A的最新审计基线；**B设计位于`docs/v12-b-order-design`**并继承它；本地执行前`git fetch`，检查最新远端并对比`git status -sb`，不得覆盖未提交工作。
- **按顺序阅读**：根`AGENTS.md`首段当前任务、`docs/reviews/v12-a-rabbitmq-static-review-2026-11-10.md`、`docs/design/v12-architecture-decisions.md`的ADR-13、`docs/design/v12-rabbitmq-adaptation.md`、**`docs/design/v12-b-order-architecture.md`**、**`docs/progress/v12-b-order-payment.md`（B0～B8唯一详细开发合同）**、旧`docs/design/v12-commerce-order-promotion-blueprint.md`仅看通用复用规则。
- 特别阅读旧A实际文件：`PortalApplicationService`、`PortalIdentityService`、`PortalOwnershipService`、`MarketCatalogService`、`MarketAdminController`、`ApiUsageRabbitConfiguration`、`ApiUsageWorker`、`ApiUsageGrantedEvent`、`ApiUsageService`、`ApiQuotaChargeService`、`V12Readiness`、`QuotaAdmissionGlobalFilter`、`sql/flowgate-v12-a.sql`；不能凭旧B/C设计误认为V12订单已有源码。
- 分支：在本地以远端`docs/v12-b-order-design`为父新建`feat/v12-b-orders`；只向该功能分支提交、小步Conventional Commits，Draft PR base=直接父设计分支。**不得改动PR#15的head/base和已有旧Draft PR、不得合并main**。

## 1. 全程必须遵守的安全/技术边界

1. 不碰用户现有`.env`、`.local`旧实例、IDE/JVM、常驻MySQL/Redis/Kafka/RabbitMQ及持久数据卷；**隔离新Compose、随机端口、新数据库和新卷**才可迁移或注入故障，STOP仅识别本任务owner，保留测试卷。不得`down -v`、DROP/TRUNCATE用户库、`git clean -fd`、`git reset --hard`、force push/rebase共享分支。
2. Java17、SpringBoot4、MVC Control/MyBatis-Plus（可复用现有PortalStore参数化SQL）、Gateway WebFlux原非阻塞、MySQL8.4、Redis7、RabbitMQ（**V12新链**）、Kafka（**V11旧C保留**）、Maven与原生ESModules，**不加新微服务、Seata、Nacos、支付SDK、Vue/npm框架**。
3. B默认`FLOWGATE_V12_COMMERCE_ENABLED=false`、`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED=false`；Portal/A功能OFF及缺B表时旧Console仍正常。Rabbit消费/拓扑只在授权隔离环境开启。数据库表只有B六张新增表，复用旧A 14表与V11全部旧表。
4. 核心Java类和业务方法准确中文注释；复杂业务长方法按`// 1.`、`// 2.`、`// 3.`逐段解释状态和事务边界；新每个阶段后端接口须同步更新**对应Portal、Console可用页面与中文指南**，不能用户端仍然是开发者工具表单。
5. **绝不按旧Kafka B/C草图编码**。B订单采用RabbitMQ独立`flowgate.commerce.v12`交换机、quorum主/TTL Retry/Poison、MySQL业务Outbox，Publisher Confirm/mandatory return后SENT，消费COMMIT后basicAck；旧A Rabbit usage链与V11 C Kafka链各自保留。
6. **交易真相全部MySQL**：免费/订单/后续抢购竞争同`fg_api_entitlement_slot(app,api,UTC月)`；只一次BASE Grant；付款模拟收据只在PENDING_PAYMENT CAS赢后写；Order`FULFILLED`只在Grant/Subscription/Balance同事务后可显示；Gateway只能从APP/API月已激活余额扣单位，不读Rabbit作实时准入。
7. 同月升级/TOPUP、自动续费、真钱支付/退款、优惠券、秒杀活动/库存、C中Redis Lua都**不属于B**。B只支持单API当月/下月BASE付费SKU与明确范围内的受控模拟退款。

## 2. 必须按顺序实施，不能跨段跳过

**B0｜把A P1-A01/P1-A02/P1-A03合并修复**
- 按`docs/progress/v12-b-order-payment.md#b0合并a的p1收口而非开一个独立a补丁项目`精确落实：Jackson3坏JSON/权威冲突→Poison；可恢复DB错误→真Retry，最多12次/永久坏Outbox停止自动重领；真实Rabbit broker停机、持卷重建、TTL/Poison、Confirm/Return和stream_queue feature验证；旧V11缺V12表的新Console市场页面给明确DISABLED/SCHEMA_NOT_READY而不500。
- **B0结束前必须在新隔离实例跑真Rabbit故障专项，保存新`docs/progress/evidence/v12-b0-*.json`并提交**。不把现有A的82分次Java与在线5笔重复标本轮全量测试；B0任何P1未证实则停止，不做B1订单。

**B1｜数据库与类型合同**
- 新增`sql/flowgate-v12-b.sql`六表：`fg_order`（唯一`principal_id+client_request_id`，订单快照，订单编号）、`fg_payment_record`（order唯一收据）、`fg_business_outbox`（eventId及aggregate版本唯一）、`fg_business_event_consumed`（eventId唯一）、`fg_order_transition`（追加审计）、`fg_simulated_refund`（order唯一）；所有字段、状态、唯一、CHECK/FK/索引完全按[架构表字典](docs/design/v12-b-order-architecture.md)。
- 校验V1→V11→C→V12-A→V12-B真实迁移顺序与**B SQL重复执行2次**，不要修改V11旧DDL/消费Ledger。实现CommerceReadiness，OFF时不查B新表、不伪装READY。

**B2｜付费SKU与前台商品**
- 扩展原`MarketCatalogService`、`MarketAdminController`和Console`market.js`，ADMIN可创建正价PAID_BASE、CURRENT_MONTH/NEXT_MONTH，不可修改历史SKU；公开API市场报价从DB展示并标示真实可售状态，免费A领取原逻辑保持。

**B3｜真实订单与幂等槽位**
- 实现`OrderApplicationService`/`OrderTransactionService`、`PortalOrderController`；`POST /portal/api/orders`只允许`{applicationId,skuId,clientRequestId}`，服务器推导用户、金额、ApiId、Period、Units、CNY、有效期。
- 先查原幂等键，锁APP→slot→检查旧V11同APP目标月重叠订阅（冲突409）→SKU报价→插订单PENDING_PAYMENT→slot HELD(holder=ORDER/orderId)、transition同事务。与A免费已有GRANTED冲突时409而不是收费；同请求重放同单、换SKU同键409；双Control下以MySQL唯一兜底。

**B4｜模拟付款与关闭**
- 实现`SimulatedPaymentService`、`OrderTimeoutCloser`，相同APP→slot→order锁序；真正MySQL订单状态条件CAS；支付同事务写唯一SIM收据和`PAYMENT_CONFIRMED`业务Outbox，关闭同事务释放仅本order的HELD槽；不在事务同步发Rabbit。15分钟支付TTL、当月距月末15分钟售卖保护、DB UTC决定边界；NEXT月不得提前Gateway调用。
- 完成实际并发支付vs取消/超时测试，确保只有一个终态、仅一张收据和一个事件；所有订单列表/详情不得泄露别的用户。

**B5｜业务Rabbit与唯一付费Grant**
- 实现独立`CommerceRabbitConfiguration`、`BusinessOutboxPublisher`、`OrderFulfillmentWorker`、`CommerceReconciliationService`，在B0验证好的持久重试分类基础上实现`PAYMENT_CONFIRMED`严格Codec与MySQL事务。
- Rabbit Consumer检查原Order/SIM收据/slot holder，经Grant`PAID_ORDER+orderId`唯一键，建立APP/API月订阅+余额、将slot GRANTED/Order FULFILLED/ConsumerDedup/Transition同事务，DB真正提交后ACK；100次重发无双Grant；旧订单已关/已退不复活；DB/Rabbit真实停恢复，SENT过300s且进程启动冷却后ADMIN可审计原eventId CAS补投。
- 已付未开通可见`PAID_PENDING_FULFILLMENT`或RETRY/REVIEW，不“模拟支付成功”就假显示API权益可用；对账检查孤儿与用户可解释原因。

**B6｜模拟退款和完整用户/运营交互**
- `SimulatedRefundService`只允许未履约（释放自身HELD）或已FULFILLED完全未用（订阅SUSPENDED、GRANTED槽保留）；不动任何Ledger；一笔订单最多一份模拟退款；已用一次拒绝；退款vsWorker/Gateway扣量需要真并发测试。
- Portal原生ESM新增订单列表/详情、确认下单、模拟支付/取消/退款与状态、实际订单→权益→Key→调用互链；Console在业务域增加订单/异常/补投运营入口；中文`docs/guides/v12-b-orders.md`及现有Portal/后台指南同步。

**B7｜只做必要综合验收，不替代C/D压测**
- Java17全模块（若真不能完整跑，逐项记录原因与实际通过）、Node Portal+Console、Python隔离器安全回归、Rabbit A usage与B commerce独立两队列、真实MySQL8.4/Redis/Rabbit/Kafka双Gateway/多Control/Worker；模拟购买→支付→权益→Gateway新配额，API-A与API-B隔离。
- **必须真测**：权限越权、同key幂等、双请求同槽、双Control争用、付款vs超时关闭、收据唯一、Rabbit坏消息毒队列与Broker重启、持卷重建、扣量前后模拟退款、GateWay真实授权、旧V11 Free5不破坏。不要将20k抢购、10万Ledger与正式D四组公平压测作为本阶段门槛。
- 记录真实退出码和匿名计数到新`docs/progress/evidence/v12-b-*.json`，旧A证据保留不可覆盖。真人1280/375手测需要单独标注负责人是否执行。

**B8｜Git交付与停机**
- 完成`docs/progress/v12-b-order-payment.md`各行真实SHA/状态/证据、README/中文帮助；按照Conventional Commits仅推`feat/v12-b-orders`并开Draft PR base=`docs/v12-b-order-design`。
- 如果原生push403，使用已经连接GitHub应用交付并核对commit tree一致；不要宣称本地提交等于远端已推送。
- **停在B8**等ChatGPT真实远端复审和负责人浏览器手动验收，不开启C高并发活动、D性能或合并main/旧PR。反馈区分B0三P1是否解决、B业务每一环是否真跑、哪些旧V11 D/E6与Rabbit故障还NOT_EXECUTED。

## 3. 交付判定

只有用户不借ADMIN，自助选择**真实正价SKU+本人APP→唯一待支付订单→模拟支付收据→Rabbit可靠事件→唯一付费权益→本月/下月有效期→Gateway按新余额准入→订单/用量可查**，且坏消息与DB/Broker临时故障可恢复、旧V11 Kafka/额度不变，才叫V12-B候选交付。A三P1必须有**真补丁+真实故障证据**才能标修复，不能靠本设计文档宣称通过。
