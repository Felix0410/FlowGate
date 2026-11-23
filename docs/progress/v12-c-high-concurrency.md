> **2026-11-23状态覆盖说明**：C0～C9核心功能已由`feat/v12-c-promo-cache@678701973a077d2dd6484405c91184e4f082fe18`实现并有定向真实提交证据，下面NOT_STARTED状态是C设计创建日的历史初始进度，**不是当前代码尚未开发**。所有剩余C可靠性P1、Rabbit12次/300秒实测、5k/20k请求阶梯、全量独立审核与负责人真人验收统一迁移到[唯一V12-D Final](v12-d-final-delivery.md)的D0～D8，后续不再单独执行C合同。唯一入口`../../CODEX_V12_D_FINAL_EXEC_PROMPT.md`；已测试/未测详情见`v12-c-implementation.md`。下方旧任务为历史追溯，不重复造新C功能。

> 实施状态更新：C0～C9候选功能与必要实测见[v12-c-implementation.md](v12-c-implementation.md)，本文件保留原合同。负责人明确要求快和准、避免过多测试，因此5k/20k阶梯、完整历史矩阵、12次耗尽与300秒SENT补投实等不再展开；这些项保持NOT_EXECUTED，不把原设计目标当作PASS。独立复审和负责人真人验收仍待完成。

# FlowGate V12-C｜高并发套餐缓存、限时抢购与Rabbit异步订单——C0～C9 Codex实施合同

> **状态：已完成设计，C0～C9代码、迁移、测试全部NOT_STARTED。** 事实基线`feat/v12-m-rabbitmq-only@d6c1f33371690071c4bb7199dc9e7db5598d8079`、PR#19 Draft；C从`docs/v12-c-high-concurrency-cache`新建设计后功能分支，PR base只认本设计。**必须按序读**[整体架构](../design/v12-c-high-concurrency-promotion-architecture.md)、[MySQL事务/DDL/故障](../design/v12-c-concurrency-transactions.md)、[Redis缓存/重建](../design/v12-c-cache-consistency.md)、[Codex唯一提示](../../CODEX_V12_C_EXEC_PROMPT.md)、ADR-15。此前旧`v12-commerce-order-promotion-blueprint.md`仅为历史规划，C最新版以前述4份为准。
>
> 设计约束：**缓存是正式必做业务功能**（所有公开API套餐列表/详情/报价读多写少缓存），活动Redis Lua预筛是另一层；MySQL是库存/价格/订单/权益唯一权威；RabbitMQ是唯一MQ，不允许Kafka。应用码/SDK只用现有Java17/SpringBoot4/MySQL8.4/Redis7/Rabbit3.13/Gateway/原生ESM，不加Redisson、Seata、CDC、前端构建框架。长业务代码中文注释`// 1.`、`// 2.`、`// 3.`，每段业务同步Portal/Console/中文指引。

## C0｜实际B/M基线、数据库预检和本地隔离环境

**先决项**：
1. 本地Codex`git fetch origin`、`git status -sb`、`git log -1`，确认**最新M功能代码**而非误把原M设计PR#18当唯一代码源；截至2026-11-23 PR#18与PR#19文件树完全相同但提交历史分叉，不能不审查就合并/rebase。当前M8“独立源码复审+负责人真人验收”未完成：**C不需要替M伪造已通过**，在启动C代码前必须获得负责人确认M候选基线是否可用；未获确认或发现P1阻塞则停止C代码、仅报告阻塞。
2. 只读核对A`fg_api_entitlement_slot`已允许holder PROMO、B `fg_order`硬CHECK禁止PROMOTION、B`fg_business_outbox`仅PAYMENT_CONFIRMED、B`OrderService.pay/cancel/timeout`、Rabbit`CommerceWorker`、市场`MarketCatalogService`与M Kafka-free状态；确定本阶段有意更新的文件和**绝不更动的旧V11 Ledger/M fence/Gateway Quota交易路径**。
3. 保证原普通用户Portal和Console可运行；核对本机用户`flowgate-local`常驻容器/根`.env`在PR#19已由Codex记录修改，**本轮C绝不自动再改**。脚本只创建新随机owner、随机Compose项目/端口/新MySQL/Redis/Rabbit卷；需检查旧服务PID/owner，不清理/stop它们。
4. Java17全reactor、Node9、现有B订单/M Rabbit三链必要定向测试，记录真实命令/exitCode；不能直接继承M报告的94项为本轮已经跑过。生成`docs/progress/evidence/v12-c0-baseline.json`，失败和未执行单独列，满足则进入C1。

## C1｜公开API套餐Redis Cache-Aside——高并发基础能力

**必须先把缓存做成业务功能，再开始秒杀**。建议新增：
- `flowgate-control/.../portal/CatalogCacheService.java`（公共投影、Redis热键、TTL）
- `.../portal/CatalogCacheKeys.java`（限长、参数hash、代次键）
- `.../portal/MarketCatalogService.java` 对`categories/search/detail/offers`拆出public DB投影和仅公开缓存包装；B的`paidOffer/claimOffer`和免费领取的权限/DB验证不能缓存。
- `.../portal/MarketAdminController.java`与`MarketCatalogService.publish/save/sku/category` 事务提交后失效/代次，不能在事务内删除缓存后回滚导致长时间错误展示；`static/portal`和`static/console/market.js`显示真实免费/正价套餐和活动入口占位，不能提早画“库存已售罄”假数据。

**必做策略**：目录120±30s、列表20±5s、详情30±10s、offers20±5s、负缓存确定404 3～5s；搜索<=50字符、limit<=20、仅热前几页缓存、最大缓存payload32KB；随机TTL、token Lua单飞、短有界等待、Redis失联时有限DB回源与连接池保护；慢回源用version/generation CAS不能盖新发布缓存；User/APP/Quota/订单不存缓存。开关`FLOWGATE_V12_CATALOG_CACHE_ENABLED=false`默认关闭，OFF B/A市场行为完全不变。

**测试**：两Control缓存同一热点商品仅少量DB读（计数真实可得）；新增SKU/上架/下架后事务提交失效；慢Reader读取旧version后管理员发布新version不能覆盖；黑盒缓存击穿50并发、穿透500随机ID不形成无限缓存键、随机TTL过期不雪崩、Redis故障限流回源、商品API owner不因缓存越权；空状态不500。保存`v12-c1-catalog-cache.json` + Node Portal/Console 真实接口证据。

## C2｜促销持久表、兼容B的CHECK与活动管理

**建议新文件**：`sql/flowgate-v12-c.sql`与小型幂等`scripts/v12-c-migrate.py`（真正DDL安全工具）、`.../promotion/PromotionAdminService.java`、`PromotionStore.java`、`PromotionReadiness.java`、`PromotionController.java`（ADMIN）。
- 仅新建5张C表：Promotion、PromoStock、PurchaseIntent、PromoOutbox、PromoConsumed；旧B Order`CHECK(source NORMAL AND promo_intent_id NULL)`必须识别实际MySQL约束名、保留其他CHECK，新增PROMOTION支持与Intent FK。必须显式全新隔离库先运行 V1→V11→V11-C→V12-A→V12-B→V12-M→V12-C，重复两次C、模拟迁移到一半后恢复，所有B旧普通订单仍可支付。
- ADMIN对真实已发布PAID_BASE SKU建DRAFT，冻结活动折扣分、单位、目标UTC月、起止UTC、库存、epoch和限购一人一次。READY/RUNNING/PAUSED/ENDED以及REBUILDING状态必须显式CAS并审计；创建完成且Redis/业务Rabbit Ready才发布，不可直接UI提交后假活动可抢。优惠价`1..sku.price_cents`、固定元→分展示，SQL CHECK和服务端校验；价格、SKU与库存发布后不可变，改价只能新活动。
- `GET /admin/promotions`与`POST/PATCH/publish/pause/resume/end`明确ADMIN权限，OPERATOR/VIEWER只能读；普通Portal只读已发布活动。默认`FLOWGATE_V12_PROMOTION_ENABLED=false`，OFF/零C表时安全空态DISABLED/SCHEMA_NOT_READY/PREREQUISITE_NOT_READY，不抛缺表500。
- **该段前端同步**：Console在已有“API市场/客户业务”域增加“限时活动”真实入口，创建通过现有SKU下拉而非要求用户手填SQL/JSON/所有数据库ID；Portal首页真正显示有活动与未开始状态。保存`v12-c2-schema-promotion.json`及Java/Node/SQL真实约束证据。

## C3｜促销活动缓存与Redis Lua候选软库存、epoch重建

新增`.../promotion/PromotionPublicCacheService.java`、`PromotionCandidateGate.java`、`PromotionRedisRebuilder.java`、`PromotionCacheDiagnostics.java`，Lua资源`src/main/resources/redis/promo-candidate.lua`、`promo-compensate.lua`、必要的原子重建Lua；只用现有Spring Data Redis的`DefaultRedisScript`。
- 活动公开详情3～5s缓存（只含真实冻结公开商品价/名额上限和状态）+hot列表，只做显示；买入绝不使用缓存价、Redis剩余数绝不作为真实库存。
- 同活动Redis gate Key`epoch/state/remaining/candidateToken/userLease`，Lua一次原子校验并`DECR softRemaining`、设置5s请求候选。UNIQUE activity+principal/DB Stock CAS兜底；DB回滚后的compensate须`epoch + token`CAS最多一次、Redis失败允许欠卖但不超卖。
- **Redis重启恢复**：DB活动REBUILDING/epoch++、等所有旧共享锁事务结束，从MySQL total-reserved-sold核账再原子设置新Redis Key，恢复RUNNING并启用gate READY；旧候选epoch不符必须拒绝，旧候选补偿不能增加新库存。Redis短断对抢购必须503（不可直接DB抢），普通公开浏览仅有界DB回源、已受理Intents/付款仍可继续。
- 两Control并发重建、两个ADMIN同时发布、TTL过期、NOSCRIPT/EVALSHA→EVAL、Lua补偿重复和Redis重启丢RDB的真实验证；监控命中/缺失/降级/失效/重建次数来自真实计数，不能画假的命中率。保存`v12-c3-redis-gate.json`。

## C4｜Portal抢购HTTP202持久Intent/APP槽/MySQL库存

新增`.../promotion/PromotionClaimService.java`、`PurchaseIntentService.java`、`PromotionPortalController.java`；不扩Gateway、QuotaCharge或V11 Ledger。

**接口** `POST /portal/api/promotions/{id}/claim`只收`{applicationId,clientRequestId}`；Portal的token取用户、检查OWNER/APP ACTIVE。先查同用户已有Intent并按fingerprint幂等；无Intent再Lua候选。MySQL事务按APP→Slot→Promotion FOR SHARE/epoch/UTC→Intent唯一键→Stock CAS reserved++→insert Intent(ACCEPTED)→Slot HELD(PROMO,intentId)→PromoOutbox(PROMO_INTENT_ACCEPTED) 同事务COMMIT才HTTP202；失败不202、Lua安全补偿；原免费/正价APP/API/月BASE Slot或旧V11同月APP订阅全部拦截。
- 用户丢202响应时`GET /portal/api/promotions/{id}/my-claim`、`GET /portal/api/purchase-intents/{id}`由MySQL回答，同请求可重复返回原Intent；跨用户404，任何用户无权限看其他人的抢购/后台事件。
- 若重复相同principal不同APP/不同clientRequestId，仍按一人一场只允许原Intent，不可重复预留。按UTC一场对应**固定月**，禁止跨月偷偷换下个月；单次API只能申领一个SKU当前冻结份额，活动时间已结束不能新抢。
- Portal显示“正在受理/排队创建订单”，不是“购买成功”。C4仅到持久Intent，不要求此段Rabbit先建订单；同步可见页面+中文帮助。保存`v12-c4-claim-intent.json`：MySQL库存/Slot/Outbox同事务、HTTP丢响应、两账号/双Control/50并发同用户、DB宕机Lua成功不202。

## C5｜独立Rabbit促销Intent队列、异步创单和孤儿回收

新增`.../promotion/PromotionIntentEvent.java`、`PromotionOutboxPublisher.java`、`PromotionIntentRabbitConfiguration.java`、`PromotionIntentWorker.java`、`PromotionOrderCreationService.java`、`PromotionIntentTimeoutCloser.java`。
- 与B共`flowgate.commerce.v12`**DirectExchange**，新`flowgate.commerce.v12.promo.intents/retry/poison`三quorum，路由`promo-intent`，不把活动消息放`flowgate.commerce.v12.orders`或V12用量队列。MySQL PromoOutbox租约、Confirm+mandatory return、独立manual ack COMMIT后，死信at-least-once+reject-publish、5s TTL重试、不可解析Poison/未知最多12次；真实Rabbit3.13.7拓扑和feature flags验收。
- Worker验Outbox original eventId/payload sha与Intent ACCEPTED、冻结SKU/价格/period、Slot HELD(PROMO,intentId)，在期限内**唯一**插`fg_order(source=PROMOTION,promo_intent_id=intent.id,价格=intent.price_cents,status=PENDING_PAYMENT)`并将Slot holder转ORDER，Intent ORDER_READY+orderId，PromoConsumed同事务。普通B用户输入`pr_`服务端保留前缀禁止，Promo Order生成唯一`pr_<UUID>`，不可能被普通B人伪造绕过。
- Closer独立按`ACCEPTED AND creation_deadline_at<=DB_UTC`有界扫描<=20，APP→Slot(PROMO)→Intent→Stock统一CAS转EXPIRED、释放原Slot和reserved--。Rabbit太慢导致超时不许复活；Worker与Closer竞争只一方成功。Rabbit重启/消息丢ACK/Consumer并发100次重复、真实Poison/DB短断/Confirm Return、SENT未消费300秒+Admin审计原eventId补投均测试。
- Portal订单待创建/异常/失败状态必须从MySQL真实查询，Console有队列毒消息和待办运营空态。保存`v12-c5-rabbit-intent.json`。

## C6｜B订单付款/超时/退款与活动库存联动、唯一权益

**只最小改**`.../commerce/OrderService.java`、`OrderTimeoutCloser.java`、`SimulatedRefundService.java`、`CommerceService.java`的来源展示/核账，以及订单/Portal实际相关页面。NORMAL分支与既有V12-A免费绝不能回归。
- 对PROMOTION订单按APP→Slot→Order→Intent→Stock锁序，一个MySQL事务将待付订单模拟付款/PaymentRecord+原B PaymentConfirmed Outbox+Intent.PAID+reserved--/sold++提交；Rabbit B旧Worker仍做唯一PAID_ORDER Grant/Subscription/QuotaBalance，不再新增第二支付系统。
- 取消/超时（仅PENDING_PAYMENT）Status CAS+Intent CLOSED+reserved--+只释放原ORDER holder Slot同事务；IntentCloser只负责尚无Order的ACCEPTED避免双减。付款 vs 超时只一方赢；重复付款100次一张SIM收据；月末/下月正常B period限制保留。
- 已付且未用能按B模拟退款规则，PROMO Intent REFUNDED但sold**不返售**；使用过409，Ledger不可逆，已Grant未用Suspend但GRANTED Slot不复售。重复退款幂等；Worker late/Refund/Gateway真实并发锁序不引死锁。
- 前端用户选APP→抢购→待创建→订单→模拟付款→唯一权益→Gateway真实扣额与历史用量，运营能查真实已售/预留/待开通。保存`v12-c6-payment-fulfillment.json`真实2Control/2Gateway/DB/Redis/Rabbit。

## C7｜商品读缓存可靠性、活动后台核账/故障恢复

- `PromotionReconciliationService` RR只读MySQL有限查询：stock.reserved==ACCEPTED+ORDER_READY，stock.sold==PAID+REFUNDED且有收据，PromoOutbox/Consumed/Order/Slot关联互相完整，不能对今日直接MATCH；超过1000样本UNKNOWN。不靠Rabbit队列为0就判已履约；显示`INTEGRITY_GAP`/PENDING/REBUILDING。
- Console活动管理视图：状态、冻结报价/库存/用户限购、MySQL total/reserved/sold、Redis缓存状态与epoch、订单/Intent后台列表、Poison、补投待办、真实Cache hit/miss及InvalidationFailed（仅实际计数）。后台写动作ADMIN且必须二次确认/审计，普通Portal无源ID泄露。缓存与账户权限隔离，缓存不能被恶意搜索词无限占满。
- 真实Redis FLUSHDB（**仅自有隔离Redis**）→活动暂停新受理、epoch重建后继续，商品/活动缓存失效但普通B/旧V11业务正常；Rabbit停恢复与DB故障/Redis故障交错，所有Stock最终对账；保存`v12-c7-reconciliation.json`。

## C8｜并发与缓存性能实验（测量，不伪造20k）

- `scripts/v12-c-load.py`或已有Python脚本扩展，使用新自有随机环境、固定JDK17+MySQL8.4+Redis7+Rabbit3.13、2Control/2Gateway，注入已发布真实API商品和100份活动；分**冷缓存公开读、热缓存公开读、热点过期回源、活动Lua筛选候选、真实端到端售100份**5组，独立真实样本隔离，不能把同一轮warm缓存当冷测。
- 对于“2万并发/请求”在安全隔离资源上**逐级**1k→5k→20k请求目标，不预先保证机器能承受：固定并发数、持续时间、成功/冲突/503/429、p50/p95/p99、实测吞吐QPS、MySQL查询和连接池、Redis命中率/慢查询、Rabbit队列延迟/消费积压、库存各状态与DB hash；单机资源不足明确写NOT_REACHED。
- 借助缓存后的DB SELECT比无缓存基线、抖动TTL/热点单飞的击穿保护必须有真实量化对比。**性能指标仅实验结果，不能将20k requests写成“20k QPS”或保证千万级。** 老V11-D的10万SQL与四组公平路由正式性能仍独立，C只做相关阶段场景测量。
- 测试中包含同用户重复抢、库存仅1的最终竞争、同APP普通B/free冲突、抢购窗口切换、活动Redis重建、付款/超时/退款冲突与Redis/Broker/DB停机，真实零超卖和权威库存核账。保存`v12-c8-performance.json`和失败尝试，老全历史矩阵未执行明示NOT_EXECUTED。

## C9｜完整中文用户体验、回归、PR复审与停机

- 正价/免费/促销套餐共用可理解的市场界面，Portal首页/套餐详情显示真实价格、折扣、配额、目标月、活动时间、选择本人APP、限购/排队状态、订单跳转/模拟付款/权益到账/使用量；375/1280真实Chromium与键盘Enter/Escape/Tab，不能出现要求用户手填SQL、Redis key、数据库ID/JSON。Console促销入口归API市场下，运营真实库存/待办/缓存命中与错误，不到处新建标语卡片。
- 中文`docs/guides/v12-c-promotions.md`、`docs/guides/product-console-guide.md`、README、AGENTS、V12路线图与`.env.example`同步，默认OFF/无C表旧B正常；明确这是模拟支付/没有真资金，排队/超时/恢复不可保证一定抢中。
- Java17全reactor真实退出码、Node前端回归、Python缓存/竞态/两节点/真实Rabbit故障/浏览器和核心安全回归，保留历史M原账本/原用户root env和常驻实例不被本轮测试改动；实际证据`docs/progress/evidence/v12-c*.json`、真实截图、Git SHA、JAR hash、失败和NOT_EXECUTED齐全。
- 新Codex功能分支`feat/v12-c-promo-cache`，基于本设计最新HEAD，Draft PR base=`docs/v12-c-high-concurrency-cache`，按C0～C9逐段Conventional Commits，最后`docs/progress/v12-c-implementation.md`填真实进度。**不要修改M PR#18/#19或B PR#17的head/base，不合并main、不自动进入D或用户正式部署**。ChatGPT远端源码复审和负责人Portal/Console真人手测必须单列，未执行保留待办。

## 进度表（仅设计初始化，绝不预填PASS）

| 段 | 核心交付 | 状态 | 应提交证据 |
|---|---|---|---|
| C0 | M/B核验与隔离基线 | NOT_STARTED | `v12-c0-baseline.json` |
| C1 | 热点商品与套餐Cache-Aside读缓存 | NOT_STARTED | `v12-c1-catalog-cache.json` |
| C2 | C五表及B CHECK兼容迁移、活动后台 | NOT_STARTED | `v12-c2-schema-promotion.json` |
| C3 | Redis Lua软库存、cache epoch/恢复 | NOT_STARTED | `v12-c3-redis-gate.json` |
| C4 | HTTP202持久Intent/Stock/Slot/Outbox | NOT_STARTED | `v12-c4-claim-intent.json` |
| C5 | 独立Rabbit意图创单/孤儿Intent回收 | NOT_STARTED | `v12-c5-rabbit-intent.json` |
| C6 | B付款/关闭/退款促销扩展及唯一Grant | NOT_STARTED | `v12-c6-payment-fulfillment.json` |
| C7 | 促销核账、恢复、缓存运营页面 | NOT_STARTED | `v12-c7-reconciliation.json` |
| C8 | 冷热缓存+限量抢购真实性能/故障实验 | NOT_STARTED | `v12-c8-performance.json` |
| C9 | 中文前端、帮助、完整回归/复审/真人验收 | NOT_STARTED | `docs/progress/v12-c-implementation.md`/截图/Review |

## 最小手动演示闭环（完成C才能执行）

ADMIN发布一个真实Gateway已发布API→创建正价PAID_BASE SKU并上架→冻结9.9元100份活动→缓存加速商品公开详情→用户从Portal选择自己APP并抢购→仅持久Intent返回202→Rabbit独立Promo队列创B类型PENDING_PAYMENT订单→前端等待→模拟付款→Stock reserved→sold、B PaymentOutbox→Rabbit B Commerce开唯一Grant→真实同一APP调用Gateway→授权后Ledger与余额→已用订单退款409、未用退款不返售→另一用户/同APP/重复请求和Redis重启、Broker故障对账无超卖。V11旧APP用量/V12-A API用量/B普通订单全部正常。

**本文件和C0～C9进度是设计，阶段未执行、性能未测。**
