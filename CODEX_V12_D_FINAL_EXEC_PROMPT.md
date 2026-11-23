# 唯一Codex执行入口｜FlowGate V12-D Final：项目所有剩余工作一次性收尾

> **最新负责人决定（2026-11-23）**：不再分别派发C收尾、M8、V11-D技术债或V12-D性能/验收；把**所有未完成内容放入最后一个V12-D Final阶段，并在同一个GitHub Draft PR完成提交**。你（本地Codex）负责编码、实测、浏览器与全部证据。**ChatGPT本轮仅创建审计/架构/任务合同并提交GitHub，不代表下面代码、性能、签字已完成。**

## 一、拿到任务立即检查的唯一资料

1. `git fetch origin`；只读`git status -sb`、`git log -1 --oneline`、`git branch -vv`、`git diff`，绝不覆盖用户本地改动。准确定位最新`feat/v12-d-final-delivery`，**直接在该现有功能分支上实现并提交**，不要再建D设计分支、D子功能分支、第二个D PR。它派生自C已实施完整源码`feat/v12-c-promo-cache@678701973a077d2dd6484405c91184e4f082fe18`；本PR的base固定`feat/v12-c-promo-cache`。任何远端已更新的D分支以最新真实HEAD为准，不硬编码设计提交SHA覆盖他人工作。
2. 严格按优先级读：`AGENTS.md`最顶部“V12-D Final”最新指令 → **`docs/progress/v12-d-final-delivery.md`（D0～D8唯一权威任务与证据要求）** → `docs/design/v12-d-final-acceptance-architecture.md` → `docs/reviews/v12-d-final-preflight-2026-11-23.md` → `docs/guides/v12-d-final-acceptance.md` → `docs/design/v12-architecture-decisions.md`最新ADR-16。旧`CODEX_V12_C_EXEC_PROMPT.md`、`docs/progress/v12-c-high-concurrency.md`与`docs/progress/v11-d-acceptance.md`仅作历史需求核对；**不要重跑旧Kafka栈/重写已完成C功能**。
3. PR#17 B、#18 M设计、#19 M功能、#20 C设计、#21 C功能仍Draft；据远端审计C两HEAD Tree相同但提交历史不同。**不要擅改这些PR base/head/分支、不force push、reset-hard、clean-fd、merge main、打tag。** 在D0提交真实Git比较报告，说明哪条C PR已包含实际代码，给负责人一套可审查/无重复合并/冗余关闭操作方案；真正调整旧PR只在用户明确另行批准后。

## 二、必须保护的资源与技术选择

- **本轮没有授权改用户常驻实例**：根`.env`、`flowgate-local`常驻Docker/IDE/JVM/原MySQL/Redis/Rabbit/旧Kafka卷、原证书/密码、旧Ledger/Outbox/Consumed/Daily不得自动更改、重启、清理或迁移。PR#19过去曾有单独本机Rabbit部署授权，不代表D测试/升级可再次操作常驻服务。测试只能在**全新随机owner/随机端口/全新命名卷**，执行FLUSHDB/SQL ALTER/故障注入/10万行压测前先assert当前Docker容器的owner且名称非`flowgate-local`；只按本轮owner停止测试资源并保留必要卷/证据。
- 严禁`docker compose down -v`在非本轮容器、`docker system prune`、`docker volume prune`、DROP/TRUNCATE用户表、强推、`git reset --hard`、`git clean -fd`、假造测试数据；`source .env`如会连接常驻数据库不得用于本轮新脚本。证据/截图/终端不能包含Bearer Token、私有账号密码/原env。
- 仅用现有Java17 Spring Boot4/Gateway WebFlux/Redis7 Lua/MySQL8.4/RabbitMQ3.13 quorum/Resilience4j/原生ESM；不增加Nacos、Sentinel、Kafka、Redisson、Seata、CDC、新微服务、新前端框架、真支付、额外付费服务。已经存在Redis TokenBucket+LocalConcurrencyLimiter+Resilience4j，不要叠一套默认开启的限流/熔断体系。更新技术决策只能根据实测瓶颈最小补丁。
- 源码中文注释要可读，长逻辑分点`// 1.`、`// 2.`、`// 3.`清楚解释锁顺序、唯一约束、出错路径，不能把30个SQL压到一行不给可维护性。后端每段变更同步Portal/Console相应页面及中文帮助；禁止假KPI、假就绪标语、强迫用户手填JSON/DB ID/Redis Key。

## 三、执行D0～D8（一个最后阶段、一轮任务、逐段真实commit）

**D0—远端PR/本机环境/真实性冻结**：
- 逐一验证PR#17～#21 SHA、Tree、diff、branch-base与合并祖先；记录C#20/21同tree与#20标题“仅设计”误导，不能把#21 diff=0当新功能未实现。V11-D历史C/E6、M8、C真人验收均标独立待办；先跑当前JDK17 Maven verify、Node9（实际自动发现的test命令）、Python unittest和Rabbit-only Compose config/依赖树。确认原root.env的hash仅只读而不在证据泄漏内容，部署测试在新owner而非旧本机8081。
- 保存`v12-d0-preflight.json`（真实命令exitCode，首次失败）和`docs/reviews/v12-d-pr-stack-audit.md`。

**D1—C可靠性P1必须修复**：
- 源码`flowgate-control/src/main/java/com/flowgate/control/promotion/PromotionService.java`中`claimOutbox`过滤`attempt_count<8`且`replay`只收SENT，导致`FAILED>=8`永久阻塞；优先无新SQL表方案：在Console事件读取的响应中以`RETRY_EXHAUSTED`可识别派生状态展示（DB继续FAILED），扩展现有ADMIN审计replay在安全条件下支持FAILED上限后的受控重新排队，保留原eventId/payload/Intent/活动原价，验证outbox未Consumed且原租约已释放、进程启动/冷却条件、数据库CAS保证仅一个管理员获胜；`FAILED_PERMANENT`不自动重试，永不凭MQ重扣库存/重复建订单。
- 真实隔离Broker连续不可用达8次→状态可见→管理员有审计恢复；同时验证过期Intent晚到IGNORED_TERMINAL、重建Stock/Slot正确、双ADMIN并发、角色403、坏JSON Poision和相同eventId重复100次；300秒真实冷却不要改系统时钟或直接UPDATE生产DB假测。新增JUnit与隔离Rabbit脚本，`v12-d1-outbox-recovery.json`真实exitCode/状态变迁。这个P1未通过**不得跳到性能标全完成**。

**D2—完整Rabbit-only M/B/C/V11故障/权限/账本复核**：
- 新隔离实测旧V11_APP: Free5五次200/六次429及Ledger/Outbox/Consumed/Daily；新V12_APP_API: Free不同API隔离与Quota；B普通Order→模拟付款→Rabbit BusinessOutbox→唯一PAID_ORDER Grant/余额/2Gateway与已用退款409；C缓存→Lua→DB202→Rabbit Promo Queue→一单一权→付款/取消/超时/Redis丢失重建；核查四种Rabbit队列main/Retry/Poison、Confirm+Return、ACK after DB commit、消息乱序、DB pause/unpause、重复消费100次、真实12重试耗尽和SENT历史补投（不把Queue空当已Consumed）。
- 旧B23/C26故障场景如现行脚本Kafka-only，将原场景映射到Rabbit-only等效脚本，不要为“让旧脚本绿”又启动Kafka，也不要把历史2026-11-08 Kafka数据冒充本轮。角色权限/旧网关混版/发布回滚/熔断半开/取消归还Semaphore/跨UTC日与月、Slot竞争、Ledger不能超额均真实核验。
- 保存`v12-d2-four-rabbit-recovery.json`、`v12-d2-legacy-regressions.json`、`v12-d2-identity-rollback.json`。

**D3—≥100000行SQL与稳定游标/索引实验**：
- 从旧`docs/progress/v11-d-acceptance.md`继承10万SQL、`EXPLAIN ANALYZE`、无界SELECT限制和稳定`(created_at,id)`游标；独立新MySQL seeded数据（含满足外键和账本业务模型的数据），V11/V12 Ledger/Outbox/Daily/审计/订单/促销后台分页与深页对照，实测rows examined、p95、索引开销与数据量，按实际查询仅补最小索引。保存`v12-d3-sql-100k.json`和脱敏原始性能表。旧用户库不能迁移。
- 不能用cache hit声称SQL优化；测试数据至少100000条，不够写NOT_REACHED+原因。

**D4—真实Gateway四组性能公平对照**：
- A DIRECT（Demo直连），B PROXY（Gateway无治理/计量），C GOVERNED（Redis限流+并发+Resilience4j），D METERED（C上新增MySQL quota）。D分别V11_APP和V12_APP_API。统一fixed fast/slow/compute请求、方法/byte、warmup、到达率/inflight、至少两轮。统计`planned/sent/clientShed/completed/业务2xx/429/503/504`和成功及全部请求p50/p95/p99、实际完成QPS、控制层quotaHop p95、CPU/RSS/DB锁等待和连接池/Rabbit lag；特意耗尽5/6次另测，不混入性能p95。按证据最小优化，修改前后同负载复测。保存`v12-d4-gateway-four-way.json`；不能用V1纯代理指标冒充V12计量。

**D5—高并发Redis缓存/100库存抢购完整负载**：
- 不把C的120请求约98 QPS或1000热读1488 QPS当D实测；新活动+隔离身份分级执行`1000→5000→20000 HTTP请求数`目标，concurrency可按机器资源从24/64/128逐级调整，任何超资源阶段写NOT_REACHED。统计完整HTTP completed/shed/202/409/429/503、p50/95/99/实际QPS、MySQL Stock/Intent/Order/Payment/Grant/Consumed/Outbox/Slot并零超卖；异步队列跟踪到收敛。
- 冷/热套餐列表/详情/优惠价/活动页缓存，negative-cache/击穿/雪崩/代次并发失效、Redis停恢复/epoch REBUILDING/NOSCRIPT，真实测量公开投影DB_LOAD与**全库SQL**差别，断连下有限DB读回退且抢购503。保存`v12-d5-catalog-promo-load.json`和脱敏原始样本/系统指标。

**D6—运营与用户工作台可完成所有真实流程**：
- Console真实Rabbit四链积压/Poison/失败/RETRY_EXHAUSTED/可补投时刻、MySQL quota/daily/订单/活动库存、Redis Cache hit/miss/invalidated、Gateway route/CB状态；不造假KPI，未知/未初始化显示UNKNOWN/NOT_READY。
- Portal市场查看上架API/free/paid/promo、真正选择自己的APP、抢购202排队→订单付款→唯一权益→Gateway扣量→余额/退款/超时；Console ADMIN录入真实API和活动、暂停/恢复/poison审计；不满屏标语、不手输DB ID。
- 真实Chromium 1280/375、键盘Tab/Enter/Escape、导航/空态/401/403/404/409/429/503/504/低速网络、敏感Token一次性显示与截图保护。保存`v12-d6-product-and-diagnostics.json`+截图。

**D7—全量回归、部署runbook与交付证据**：
- Java17 `mvn -B -ntp verify`、Node各入口、Python unit+全部新老Rabbit-only HTTP故障脚本、SQL/缓存/性能原始结果；最初失败和之后成功都保留，旧V11/V12 A/B/M/C业务和快照回滚不退化。
- 新环境真实空卷安装B/M/C表、默认OFF旧业务安全、STOP→持卷START双Control/双Gateway Rabbit故障后对账；编制操作手册，包含操作权限/备份/参数/回滚/常驻环境只读诊断/启动停止owner语义。中文README/AGENTS/Portal/Console指南更新，不自动动本机`flowgate-local`或root.env，也不自动永久启用C。
- 保存`v12-d7-full-regression.json`、`docs/guides/v12-d-final-runbook.md`，运行证据按源SHA/JAR SHA/exitCode/安全owner落实。

**D8—最后完整源码复审/人工验收/一个PR终点**：
- 新建`docs/progress/v12-d-final-implementation.md`、`docs/acceptance/v12-final-results.md`、`docs/reviews/v12-d-final-source-review.md`，真正填D0～D8 PASS/FAILED/NOT_EXECUTED/NOT_REACHED，不用空白/猜想/旧日期覆盖缺口。
- 最后保持一个`feat/v12-d-final-delivery`和同一个Draft PR(base`feat/v12-c-promo-cache`)，按用户审批准备旧PR安全合并链/冗余关闭方案，但不自动合并/改旧ref/main/tag；交用户真人1280/375及业务回归清单。**Codex录屏截图只能证明自动浏览器，不等于负责人本人完成手测**；没有用户亲自确认就在最终报告标`PENDING_OWNER`。
- 所有剩余工作在此阶段收口，**禁止再创建V12-E/新框架集成支线**。若P1未通过/账本不一致/核心测试失败，最终状态`BLOCKED`；如果受机器限制不能达到负载，`CONDITIONALLY_READY`必须列实测值和仍需用户决定的范围，绝不写“20k QPS”。

## 四、完成后的汇报格式（严格事实，不要仅发“已提交”）

请向用户返回：
- 同一Draft PR URL、base/head/latest SHA、改动文件摘要、是否覆盖旧PR/main、D0～D8每包真实commit与结论。
- P1恢复事件的**原eventId**、发送尝试次数、恢复前后状态、真Rabbit confirm+消费标记、Slot/库存、同事件重复结果（日志仅脱敏）。
- V11免费5/6、V12-A/B/C全链与四Rabbit正确性、100库存零超卖，10万SQL真实执行计划与对比、Gateway DIRECT/PROXY/GOVERNED/METERED实际吞吐/p95、C请求阶梯何档达成与客户端shed/真实DB访问。
- Java/Node/Python/Chromium/容器恢复每条真实exitCode、通过/失败/未运行、系统资源限制、部署默认OFF/隔离owner、截图文件与runbook链接。
- 未完成的负责人真人手测/独立审核/PR合并明确标`PENDING_OWNER/PENDING_REVIEW`；没有凭空承诺“今天已在生产上线”。直接交成果，不在后台异步排队完成。

**请在一个连续开发任务中走完 D0～D8，不为每包另开业务需求询问或新的PR；遇安全权限/用户常驻资产阻塞必须停相关危险动作并采用隔离替代，严禁越权。**
