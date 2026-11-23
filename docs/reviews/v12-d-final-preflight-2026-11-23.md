# V12-D Final｜整体验收前源码审计与欠账冻结（2026-11-23）

> **结论：只完成GitHub远端源码与提交证据检查，没有在用户机器上重跑本地/真实浏览器/压测。** 本页是最后阶段的事实基线，绝非D阶段实现/验收PASS。用户已决定**将V12-C收尾、V12-M剩余验收、V11-D历史压测以及V12-D性能、可靠性、部署和最终交付统一并入一个最后阶段V12-D Final**；以后不另派业务开发阶段。
>
> 当前继承源码：`feat/v12-c-promo-cache@678701973a077d2dd6484405c91184e4f082fe18`，最后阶段唯一开发分支`feat/v12-d-final-delivery`；Draft PR拟以`feat/v12-c-promo-cache`为base，不改已有main/其他PR及用户常驻本地环境。

## 1. 当前分支与Git风险

| PR | 当前用途 | 已观察状态 | 收口原则 |
|---|---|---|---|
| #17 `feat/v12-b-orders` | B真实订单/Rabbit Commerce | Draft，HEAD `c857d3b8532ae98dcfb208d10dd140efa0a3037b` | 原历史保持，不能重写/先行合并 |
| #18 `docs/v12-m-rabbitmq-convergence` | 原M设计 | Draft，设计分支后来与M功能树重叠 | 不强推重建共有分支，最终审查明确重复内容 |
| #19 `feat/v12-m-rabbitmq-only` | M Kafka-free功能 | Draft，HEAD `d6c1f33371690071c4bb7199dc9e7db5598d8079` | 保留Rabbit-only准入与MySQL旧账本，M8独立验收未签 |
| #20 `docs/v12-c-high-concurrency-cache` | 原C设计 | Draft，但当前HEAD `97a01d436df37e4e48b64a53bf3e2012c4423c9d`与C功能分支最终Git Tree相同 | **不是纯文档PR**，review不可照旧PR标题；无强推、无直接改base/head |
| #21 `feat/v12-c-promo-cache` | C完整业务功能 | Draft，HEAD `678701973a077d2dd6484405c91184e4f082fe18`，与#20最新Tree相同、不同提交SHA/parent，原两分支commit compare显示5 ahead/5 behind但文件差异空 | 无内容差异不等于两个PR都需要合并；待owner确认后只选**一条**完整C实际内容作为合并事实，另一条按冗余PR保留历史/关闭，无force push |
| 本轮D PR | 最后剩余代码、测试、文档和验收 | **此时仅任务书提交，功能/压测NOT_EXECUTED** | 一个连续D功能分支、一个Draft PR直到用户确认 |

**处理顺序**：先在D0只读比对Git refs/trees/PR diff/merge-base，列出B→M→C→D的完整文件树及实际commit来源；不要试图为“漂亮的线性历史”擅改旧PR head/base、reset-hard、force push或自动merge。后续可在审查报告里将#20/#21重复树具体解释清楚，并给用户“先合并哪个、另一个何时关闭”的**人工操作方案**；真正合并须用户明确许可。

## 2. C候选已实现的事实（不能再标NOT_STARTED）

源码已存在：
- `flowgate-control/portal/CatalogCacheService.java`、`MarketCatalogService.java`：公开API套餐列表/详情/报价Cache-Aside、跨Control共享Redis、代次失效、短锁/负缓存、有界回源；SKU/订单真实价格仍MySQL读取。
- `flowgate-control/promotion/PromotionService.java`、`PromotionCandidateGate.java`、`PromotionOrderStockService.java`、`PromotionIntentWorker.java`、`PromotionAdminService.java`，C五表、`sql/flowgate-v12-c.sql`及`scripts/v12-c-migrate.py`；Lua脚本softCandidate/compensate/rebuild；MySQL Stock+Intent+Slot+Outbox提交后202；独立Rabbit Promo main/retry/poison；B模拟付款/关闭/退款联动stock，sold退款后不返售。
- 真实Portal/Console原生ESM、中文指南、隔离预览脚本`scripts/v12-c-preview.py`。
- 已提交证据声称：Java17 94项，Node9入口、Python14；100库存120个隔离身份HTTP请求，100 Intent、100唯一订单、10模拟付款、10 Grant，`reserved=90,sold=10,available=0`；公开缓存1000请求24并发实测约1488 QPS/公开投影DB_LOAD增量0；抢购120请求约98 QPS。记录见`docs/progress/v12-c-implementation.md`、`docs/progress/evidence/v12-c8-performance.json`。
- 隔离Rabbit Channel在DB COMMIT后丢ACK、重投仍1单；独立Promo Poison、TTL、Redis FLUSHDB /重建/Redis SCRIPT FLUSH、MySQL pause/unpause、2 Gateway真实200/已用退款409、1280/375 Chromium均有Codex提交证据。**这些数据不能冒充D独立重跑/负责人真人签字**。

## 3. 已知P1与验收欠账（不许隐藏）

### P1-D01：促销Outbox长故障重试耗尽无恢复入口（有实际源码依据）

`PromotionService.claimOutbox()`只取`attempt_count<8`；失败`failed(...,false)`把事件存为`FAILED`，第8次后不再进入取件循环；`replay()`目前仅允许`status='SENT'`且未Consumed的事件。于是经8次真实Broker发送错误后`FAILED + attempt_count>=8`虽然仍有MySQL权威Outbox，却既不自动恢复、也不能按现有ADMIN入口补投。仅“Rabbit恢复后重启进程”并不能改变数据库过滤条件。要在D的前两个检查点修复，优先仅使用旧schema的`FAILED + attempt_count>=8`作为`RETRY_EXHAUSTED`**派生告警态**，在明确权限+审计+冷却+原eventId/Intent/Outbox指纹+未Consumed核验后支持受控恢复；不得误将`FAILED_PERMANENT`坏消息自动重排。对已过期Intent补投应被消费者合法终态忽略，不二次建单/扣库存。若实现选择新增SQL状态，必须有独立幂等隔离DDL并说明代价，默认不扩表。

### P1-D02：提交树/PR边界异常（非业务代码bug）

PR#20 / #21已出现**同Tree不同提交历史**；因此#21可能没有实质文件增量，同时#20标“仅设计”却承载全套业务源码。不能机械地“先#20再#21”合并；D0给出无数据破坏的review/close/merge决议，最终只有一份C事实进主线。

### P1-D03：最终可运行验收与性能缺口

- M8独立源码复审及负责人真人测试：未签收；C独立源码复审/负责人Portal+Console手测：未签收；C文件已写`PENDING_REVIEW`。
- C `v12-c9-regression-browser.json`明确：**5000/20000请求阶梯、完整历史B23/C26、Rabbit12次重试耗尽、SENT300秒真实等待**均未执行。当前C“98 QPS抢购/1488 QPS热读”为小样本**不是**正式大并发性能结果。
- V11-D旧技术债：10万量级SQL/四组公平路由与限流、双Gateway极限、动态发布/熔断等正式性能验证未独立补齐；历史Kafka版本测试**不能拿来作为当前Rabbit-only版本已重跑**。旧E6人工验收待签。
- D1要先做全量独立基线、真故障/权限/缓存+Gateway性能测量，D阶段不凭历史C已有截图或结论自动PASS。

## 4. 最后阶段范围及安全授权

只允许`Java17 + Spring Boot4 + Spring Cloud Gateway/WebFlux + Resilience4j + Redis7 + MySQL8.4 + RabbitMQ3.13 + 原生ESM`现有技术栈。既有跨Gateway Lua限流、Resilience4j熔断、MySQL快照发布已工作；**不为凑中间件而加入Nacos/Sentinel/Kafka/Redisson/Seata/CDC、新前端框架、真支付**。

用户常驻`flowgate-local`根`.env`、MySQL/Redis/Rabbit自有卷、IDE JVM、旧Kafka持久卷及历史Ledger/Outbox/Consumed/日报都是保护对象。PR#19曾经在过去获得单独部署授权，**不能自动推定为D新阶段又允许操作这些资源**。D所有测试/10万SQL/Redis FLUSHDB/Broker故障/DB停机只在新随机owner、端口、命名卷的自有隔离实例执行。默认开关不在用户常驻环境打开；不允许自动正式部署或合并main。清理只处理本轮有owner的临时资源并保留证据与卷，禁止`docker system prune`、`docker compose down -v`针对原实例、DROP/TRUNCATE用户库、`git reset --hard`、`git clean -fd`、force push。

## 5. 复审判定

**截至本审计，V12-D Final仍是`NOT_STARTED`的最后实施阶段。** 其唯一合同：`docs/progress/v12-d-final-delivery.md`；唯一Codex交接文件：`CODEX_V12_D_FINAL_EXEC_PROMPT.md`；验收/压测方案：`docs/design/v12-d-final-acceptance-architecture.md`；操作清单：`docs/guides/v12-d-final-acceptance.md`。不要把GitHub文档提交等同于P1已修、性能已测或项目已正式验收。
