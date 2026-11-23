# 本地Codex唯一执行入口｜FlowGate V12-C 高并发套餐缓存、限时抢购和RabbitMQ异步订单

> **当前只完成设计，C0～C9代码与测试全部NOT_STARTED。** 用户明确要求“设计C，并且高并发套餐需要缓存”：**商品分类/列表/详情/SKU报价及活动公开页Redis Cache-Aside是必做功能**；活动Lua软候选、MySQL库存/Intent强约束和Rabbit异步创单是另一个必做环节。V12-M已将Kafka运行依赖退役，C**绝不引回Kafka**。不可把Redis预扣称为买到、不可把20k压测目标称为已实现20k QPS。

## 0. 先对齐真实Git父链、阅读4个权威文件

1. `git fetch origin`，先只读`git status -sb`、`git log -6 --oneline`、`git branch -vv`，保护未提交文件与个人IDE运行服务。**唯一设计分支**：`docs/v12-c-high-concurrency-cache`，创建自最新真实M功能提交`feat/v12-m-rabbitmq-only@d6c1f33371690071c4bb7199dc9e7db5598d8079`。PR#18/19原设计和功能分支曾有相同Git Tree而不同提交历史，**不要未经核实直接merge/rebase旧PR**；设计分支目前仅包含C文档，绝不能把旧PR base修改。
2. 完整阅读并按优先级：`AGENTS.md`的最新C段；`docs/design/v12-c-high-concurrency-promotion-architecture.md`（业务架构）；`docs/design/v12-c-concurrency-transactions.md`（B schema硬CHECK、锁序、Stock、Intent/Rabbit、故障）；`docs/design/v12-c-cache-consistency.md`（必做Redis读缓存/软库存/重建）；**`docs/progress/v12-c-high-concurrency.md`（C0～C9唯一执行合同）**和新增ADR-15。早期`docs/design/v12-commerce-order-promotion-blueprint.md`有已过时Kafka语句，不得覆盖新合同。
3. 先确认远端PR#19 Kafka-free运行候选的源码和M8独立复审/负责人验收状态（截止2026-11-23 M8待人工确认）。无法证明M候选可作C基线时**停在C0报告阻塞，不擅自修改M PR/head/base或假填M8完成**。负责人确认后，从本设计分支最新HEAD新建`feat/v12-c-promo-cache`，最后Draft PR base=`docs/v12-c-high-concurrency-cache`，逐段Conventional Commits；不动main、B PR17、M PR18/19、不自动进行D。
4. 必须实际检查源码与真实表定义：`sql/flowgate-v12-a.sql`中Slot已允许PROMO holder；`sql/flowgate-v12-b.sql`中Order CHECK只允许NORMAL且BusinessOutbox只认付款；`MarketCatalogService`的DB查询与SKU上架、`OrderService.pay/cancel/timeout`、`SimulatedRefundService`、`CommerceEvent/Service/Worker`、`OrderTimeoutCloser`、`PortalOwnershipService`、`V12Readiness`、`RabbitDelivery`、`fg_api_quota_balance/ledger`、M Kafka-free Compose、`run-local.py`与Portal/Console真实页面。**不猜测当前B是否支持促销订单**。

## 1. 绝对安全/实现边界

- Java17、SpringBoot4、Gateway、MySQL8.4、Redis7（现成Spring Data Redis脚本）、RabbitMQ3.13（Spring AMQP）、原生ESM，不增加Seata、Redisson、CDC/新服务/新前端框架/真支付。
- MySQL是权威：API发布、不可变SKU、活动冻结报价、库存、APP Slot、Intent、Order/Payment/Grant/QuotaLedger都在真实MySQL判断；Redis**只能**缓存公共读投影和抢购临时软候选（可欠卖不超卖），不作为价格/用户资格/是否已付款或Gateway扣额真相。Rabbit仅异步投递，必须保留MySQL Outbox+消费幂等。
- 任何代码/脚本不得改用户根`.env`、常驻`flowgate-local`本机Docker/JVM/IDE、用户现有MySQL/Redis/Rabbit/Kafka旧卷/备份、历史Ledger。PR#19本机部署记录不授权本轮C动常驻环境。**C测试只使用新的随机owner、端口、项目名及新命名卷**；禁止`down -v`对用户项目、DROP/TRUNCATE用户表、reset-hard、clean -fd、force-push、docker system prune/volume prune。不要在有未提交修改时覆盖。
- 默认`FLOWGATE_V12_CATALOG_CACHE_ENABLED=false`、`FLOWGATE_V12_PROMOTION_ENABLED=false`；C表不存在/OFF时旧B订单/免费领取/M V11和V12用量及Console必须安全工作，不新增缺表500。未经负责人授权不能把C服务开启在日常数据库或自动执行C ALTER。
- 每段业务写真实Portal/Console与中文帮助而不是最后才做简陋前端；业务大方法`// 1.`、`// 2.`、`// 3.`中文解释异常/锁序/幂等。用户端选自己的APP/SKU，禁止输入原始数据库ID/Redis key/JSON。
- 每段测试用自有MySQL/Redis/Rabbit/2Control+2Gateway真实HTTP与浏览器，性能/并发不是Mockito就算完成；保留失败尝试、源码SHA、运行命令/exitCode、NOT_EXECUTED。ChatGPT远端只读审计也不冒充亲自运行用户机器。

## 2. 唯一实施顺序：C0→C9

**C0 基线**：核查M8/PR#19、最新代码、B来源硬CHECK及Rabbit实际队列、新项目owner保护，跑Java17/Node/定向旧B/M回归，保存`v12-c0-baseline.json`。M未确认不可静默跳过。

**C1 必做通用套餐读缓存**：新增`CatalogCacheService`并拆`MarketCatalogService`公开投影，只缓存categories/search/detail/offers公开字段（含付费SKU分价/额度/period/商品revision）、商品详情/活动Public Cache；TTL抖动、404负缓存、单飞热点锁、限制搜索分页和缓存大小、事务提交后失效/代次CAS防慢Reader覆盖、Redis不可用时有界DB回源。**B普通订单仍查MySQL锁行价格和API真实Ready**；同步Portal商品页与Console。分别测试两Control冷热缓存、击穿/雪崩/穿透/写后失效/坏Redis。保存`v12-c1-catalog-cache.json`。

**C2 C表及活动管理**：`sql/flowgate-v12-c.sql`新增5表（Promotion/PromoStock/PurchaseIntent/PromoOutbox/PromoConsumed），活动DRAFT→READY/RUNNING/PAUSED/REBUILDING/ENDED。原B`fg_order`有未命名CHECK限制`source=NORMAL AND promo_intent_id IS NULL`，必须**从information_schema精确识别**后安全DROP原CHECK/ADD命名兼容CHECK和FK，二次DDL与中断续跑真实测试；不动普通Order原行和其他CHECK，旧B正常业务无回归。ADMIN从真实PAID_BASE SKU创建冻结活动报价、库存、固定UTC月，价格整数分，普通用户只读已发布真实活动。保存`v12-c2-schema-promotion.json`。

**C3 Redis候选+重建**：新`PromotionCandidateGate`+`PromotionRedisRebuilder`与两份Lua脚本；带MySQL活动epoch、Redis gate READY、softRemaining、5s候选token/用户租约，Lua原子DECR仅候选；DB失败才按epoch+token CAS补偿（最多一次），DB成功不返Redis；MySQL REBUILDING epoch++阻断旧事务并据权威`total-reserved-sold`重建Redis，旧epoch候选即使Lua成功也必须DB拒绝；Redis丢失抢购503不旁路，无条件限量DB读取公共页面。真实Redis重启/FLUSHDB/NOSCRIPT/两Control/EVALSHA fallback通过。保存`v12-c3-redis-gate.json`。

**C4 持久HTTP202受理**：`POST /portal/api/promotions/{id}/claim {applicationId,clientRequestId}` 校验本人APP、同活动一人一个、读MySQL已有Intent同参重放，Lua赢后MySQL APP→Slot→活动FOR SHARE/epoch/DB UTC→Stock CAS reserved+1→Intent ACCEPTED→Slot HELD(PROMO,intentId)→PromoOutbox同事务COMMIT，**只此时202**。客户端HTTP丢包后`GET my-claim`/Intent详情查MySQL，不重抢；跨用户404，和普通B/free/同月旧V11订阅互斥。Portal同步“排队，未付款未开通”。保存`v12-c4-claim-intent.json`。

**C5 独立Rabbit创建订单**：`flowgate.commerce.v12` direct exchange新增Promo Intents/Retry/Poison独立quorum三队列，不复用只认PAYMENT_CONFIRMED的B Commerce queue/Outbox；PromoOutbox publisher Confirm且mandatory无Return后SENT，PromoWorker核对DB源eventId/Intent/Slot，同事务唯一插`PROMOTION`订单(原`fg_order.promo_intent_id UNIQUE`，金额来自冻结Intent)，Slot PROMO→ORDER、Intent ORDER_READY、Consumed，COMMIT后manual ACK；严格永久Poison/有限12次TTL retry。Intent过期未建单Closer依APP→Slot→Intent→Stock释放reserved/Slot，晚到Worker不可复活，历史SENT补投原eventId ADMIN审计/300s冷却。保存`v12-c5-rabbit-intent.json`。

**C6 延伸B付款/取消/退款**：只对`source=PROMOTION`扩展原`OrderService.pay/cancel/timeout`与`SimulatedRefundService`，APP→Slot→Order→Intent→Stock行锁，付款CAS+唯一SIM收据+B PAYMENT_CONFIRMED Outbox+reserved--/sold++同事务；取消/超时只reserved--，与付款只有一方赢。已付退款**不返售sold**，未用B规则、已用409，Ledger不动；B原Commerce唯一Grant和Gateway额度继续复用，NEXT_MONTH不能提前扣。保存`v12-c6-payment-fulfillment.json`。

**C7 对账/后台/恢复**：用MySQL RR有限查询核对`stock.reserved==ACCEPTED+ORDER_READY`、`sold==PAID+REFUNDED(有Payment)`、Intent↔Order↔Payment/Outbox/Consumed↔Slot/Grant；超过1000UNKNOWN，不把今日称MATCH。ADMIN真实活动编辑/暂停/库存审计/Redis rebuild/Poison与Outbox补投；OPER/VIEWER只读；两Control Redis失忆恢复和Rabbit断连、旧V11/M/普通B同时正常。真实页面非标语风。保存`v12-c7-reconciliation.json`。

**C8 实测缓存和高并发**：冷/热套餐查询、缓存失效回源、Lua候选、全链路100库存分场分别测，2Control/2Gateway，1k→5k→20k请求**逐级探索**，严格记录真实完成请求数、QPS、p50/p95/p99、DB查询量、cache hit/miss、MySQL连接池、Stock/Intent/Order/Payment/Grant、Ruby? **不得引用不存在技术**；本机不支持20k就记NOT_REACHED，不能声称20k QPS或设计即实测。Redis/MySQL/Rabbit真实停恢复、混合重复请求不超卖。保存`v12-c8-performance.json`，与V11-D独立性能实验区分。

**C9 完成UI/指南/回归但停在Draft**：Portal真实价/单位/时间/APP选择/抢购排队→订单→付款→权益与Gateway用量、Console活动/库存/缓存/死信/补投；真实1280/375 Chromium与键盘；`docs/guides/v12-c-promotions.md`、README/AGENTS/路线图/产品指南、`docs/progress/v12-c-implementation.md`同步。Java17/Node/Python/真实MySQL/Redis/Rabbit所有预定关键回归与失败、NOT_EXECUTED如实记录。只推独立`feat/v12-c-promo-cache` Draft PR，base本设计分支；**不合并main、不重新引入Kafka、不修改旧PR head/base、不开始D、不迁移用户常驻环境**。请交ChatGPT远端源码复审并由负责人真人验收。

## 3. Codex完成报告必须包含

- 每段C0～C9实际commit SHA、是否已经推送、Java17/Node/Python真实exitCode、隔离owner/源Jar SHA256、失败与未运行矩阵。
- 数据验证：原MySQL旧M/V11 Ledger没有丢失；活动100份分布`reserved,sold,available`一致、唯一Intent/Order/Grant、免费/普通订单与C互斥；Redis丢失先阻断再重建；Rabbit 3条既有链正常、C意图新队列真实ACK/Poison/TTL。
- 缓存验证：公开SKU热读Redis缓存命中与实际DB负载改善、过期锁单飞/负缓存/TTL抖动、提交后失效/代次CAS、Readiness/订单不信缓存、缓存故障降级有界。压测性能只报真实机器实际结果，20k**可能未达到**。
- 前端/手册：不要求用户输入ID/JSON，真实API上架、商品详情、活动抢购、订单/支付与额度状态互相导航，1280/375真人验收尚未完成则写NOT_EXECUTED。
- Git安全：不改变M PR#18/19、B PR#17或main，不动根.env、原个人卷或历史账本；现行只RabbitMQ，C为设计后新功能而非旧Kafka复活。

**所有操作严格按`docs/progress/v12-c-high-concurrency.md`逐段执行。任何阻塞/真实失败要停在对应阶段，不补造PASS、不静默跳阶段、不做额外复杂框架。**
