# FlowGate V12-D Final｜最后一次完整开发、性能、可靠性与正式交付（唯一实施合同）

> **合同状态：已写入GitHub；本轮仅创建任务书/设计，D0～D8实际改代码与测试还没有执行。** 用户2026-11-23明确要求“将所有剩余内容全部放到最后一个阶段，一起提交GitHub完成”。本合同是唯一最后阶段；不再独立发“C后续修复”“M8补测”“V11-D”“D性能”“E最终”等额外阶段。D0～D8是**同一阶段内部工作包**，允许Codex一轮交付但逐段真实校验和commit，最后只有**一个**本阶段功能分支及Draft PR。

**唯一执行入口**：`CODEX_V12_D_FINAL_EXEC_PROMPT.md`。**规范优先级**：本文件→[最终技术与验收架构](../design/v12-d-final-acceptance-architecture.md)→[远端审计/已知P1](../reviews/v12-d-final-preflight-2026-11-23.md)→[负责人手测清单](../guides/v12-d-final-acceptance.md)→ADR-16→C/M/B历史合同（仅回归范围，不再执行旧阶段）。实际源码父分支`feat/v12-c-promo-cache@678701973a077d2dd6484405c91184e4f082fe18`。此分支`feat/v12-d-final-delivery`包含D任务书，之后Codex直接向它提交源码/测试/证据；Draft PR固定以`feat/v12-c-promo-cache`为base，不再创建另一个设计PR或D功能PR。保护#17～#21和main。

## 总体工作规则

- **已有功能不重做**：V1/V11 Gateway/治理、V11旧APP月配额/日统计、V12-A自助Portal/免费、B正价订单/模拟支付/退款、M Kafka-free Rabbit迁移、C Redis公开商品缓存/Lua抢购/异步订单/库存联动**均已存在代码**，本阶段是复审修复/压测/运维与验收，不重新发明数据库或替换成熟技术组件。
- **P1先修再测**：C Outbox FAILED+8次不可自动领取也不可ADMIN重放、#20/#21同Tree不同history、M/C独立复审与真实验收欠账，在D0～D2处理/登记，不得忽略。C业务仍模拟付款，不得混入真实资金。
- **不增加技术栈**：Java17/Spring Boot4/Gateway WebFlux、Spring Data Redis7 Lua、MySQL8.4、RabbitMQ3.13 quorum、Resilience4j、原生ESM；不加Kafka、Nacos、Sentinel、Redisson、Seata、CDC、K8s、Vue/React/npm构建或真实支付。
- **用户资产安全**：根`.env`、`flowgate-local`常驻JVM/Docker、用户原MySQL/Redis/Rabbit/Kafka旧卷、证书/真实密码/历史Ledger/Outbox/Consumed/Daily一律只读；任何故障注入、DB移植/ALTER、Redis FLUSHDB、满负载生成≥10万SQL均仅新的随机owner+端口+命名卷+进程。保护未提交文件/IDE和所有旧PR，禁止`down -v`针对非本轮资源、`docker system prune`、DROP/TRUNCATE用户表、`git reset --hard`、`git clean -fd`、force-push、自动合并/打tag/常驻迁移。
- **Git与证据**：按D0～D8做Conventional Commit，源码变更和同阶段Portal/Console/中文说明同提交；每段`docs/progress/evidence/v12-d*.json`记录当前source SHA/Jar SHA、Java17/Redis/MySQL/Rabbit真实版本、owner、隔离项目名/端口、资源上限、执行命令/exitCode、样本数、原始脱敏数据位置、失败首次重试、NOT_EXECUTED/NOT_REACHED。业务逻辑复杂代码中文按`// 1.`、`// 2.`、`// 3.`等编号解释事务/幂等/故障。自动化测试不能代替负责人真人手测；不编造成功量和性能收益。

## D0｜冻结最终基线 + 原PR重叠确认 + 隔离环境

**立即执行**：
1. `git fetch origin; git status -sb; git log -1 --oneline`，确认`feat/v12-d-final-delivery`HEAD继承C真实功能，所有当前未提交文件保留；逐一核实PR#17～#21实际HEAD、merge-base、文件tree、PR#20/21 diff。当前静态事实：C design与feature两head **Tree相同、提交链不同**；PR#20“仅文档”实际带全套C代码、PR#21 compare无内容差异。此任务只提交安全收敛建议，**不能擅自改/关闭/合并PR**；用户许可后可由用户或按独立明确授权处置冗余PR。
2. 从`docs/progress/v12-m-implementation.md`、`v12-c-implementation.md`及所有`evidence/v12-m*`、`v12-c*`建立“源码/证据/未运行”清单；区分B/M/C候选已实现与M8/旧E6/负责人真人复审未签。把旧V11-D`docs/progress/v11-d-acceptance.md`中10万SQL/四组公平路由场景转为本阶段真实Rabbit-only实现，不按旧Kafka脚本跑。
3. 隔离D项目必须用新owner/随机端口/新MySQL8.4、Redis7、Rabbit3.13卷/2 Control/2 Gateway/1 Demo，无用户凭证；在所有写命令前校验Docker label/owner和自有PID，确认常驻`flowgate-local`没有变化。
4. 基线真正运行Java17 Maven`verify`（不能只靠旧C94项报告）、Node原生ESM入口与安全/产品合约、Python既有unit tests、Compose config与Rabbit-only依赖树，记录exitCode和首次失败；独立新库B/M/C Readiness以及默认开关OFF旧表无500。
**退出条件**：隔离owner/环境/文件树、真实baseline和P1列表冻结；失败不能假填PASS。
**证据**：`docs/progress/evidence/v12-d0-preflight.json`和`docs/reviews/v12-d-pr-stack-audit.md`（后者在实际读全PR后写）。

## D1｜唯一剩余P1代码修复：C促销Outbox FAILED耗尽恢复 + 基础审计

文件：`flowgate-control/src/main/java/com/flowgate/control/promotion/PromotionService.java`、`PromotionController.java`、`PromotionIntentWorker.java`、必要`PromotionAdminService.java`、Console`static/console/promotions.js`及新JUnit/隔离Rabbit脚本。**优先不改五张C表结构**：保留`FAILED`数据库存储，当`attempt_count>=8`对运营派生`RETRY_EXHAUSTED`状态；已有正常`FAILED<8`有限自动重试，`FAILED_PERMANENT`坏事件不自动解封；扩展原**ADMIN专用**`/admin/promotions/events/{eventId}/replay`，在授权/审计、DB锁、单事件指纹验证、300秒冷却、同一eventId与幂等路径下允许`FAILED>=8`受控重排。不能将FAILED耗尽直接改SENT/Consumed；不能让多名管理员并发各重排一份；不能重开终态Intent；不能因Broker重启跳过MySQL原Outbox。

测试：真实Rabbit暂断直到8次FAILED；受控ADMIN补投后若Intent未过期唯一订单/Consumed，若已EXPIRED则只IGNORED_TERMINAL且reserved正确释放；两ADMIN同时操作CAS、伪造eventId和跨角色、旧SENT 300秒真实计时、发生DB COMMIT→ACK丢失100重投、坏JSON永久Poison、Broker再次离线失败明确。不能通过人工直接update用户库演示。运营页有真正`RETRY_EXHAUSTED`/消耗/待办计数，不画假恢复。
**退出条件**：Outbox不会永久不可操作，但不会无限自动轰炸Broker/丢DB事实；原B PaymentConfirmed/旧Usage队列无回归。
**证据**：`v12-d1-outbox-recovery.json`及MySQL状态/确认重投/真实ADMIN审计截图。

## D2｜M8/C独立可靠性复审 + Rabbit四链/库存/配额深度故障矩阵

- 真实运行**Kafka-free**新隔离Compose，Java依赖树无Kafka，4条逻辑Rabbit：Legacy V11 Usage、V12 APP×API Usage、B Payment→Grant Commerce、C Promo Intent。核实Outbox/Consumed/Lease、manual ACK after MySQL commit、Publisher confirm+Return、Quorum main/TTL/Poison及feature flags；Rabbit持卷重启/Confirm失败/临时DB unavailable/毒消息/重复100次/Retry12次耗尽/SENT 300s（上一步D1已涵盖可引用同阶段真证据）、消息返回后的原eventId补投、额度Ledger/Daily reconcile。
- 多Control多Gateway真实验证V11 old Free5：5HTTP200、第6次429、5 Ledger+Outbox+Consumed+Daily且UTC归属正确；V12-A免费指定API一次/同APP不同API独立、V12-B正价Order→simulatePay→B Rabbit消费→唯一Grant→2Gateway授权/退款、V12-C Redis缓存+Lua→MySQL持久202→独立Rabbit唯一订单→B付款与Slot→2Gateway调用与已用退款409；旧账本和新账本绝不互相改写。
- 两Control同时同APP/API/月免费/正常付费/促销竞争，只能一个BASE holder。支付vs取消/超时/退款与Gateway真实扣额，原Ledger不可逆；Lua DB rollback补偿一次、COMMIT结果不明不误补、Redis重启后REBUILDING/epoch新代使旧token无效；Rabbit最后消息不会复活已EXPIRED的Intent。
- Gateway保护/回滚：身份领域ADMIN/Portal/CALLER/APP_CALLER互相隔离、应用Key撤销/代次、旧Gateway混版路由校验、非计量路由旧有可靠性、发布快照版本/回滚、breaker OPEN→HALF_OPEN→CLOSED、timeout/cancel确保LocalConcurrencyLimiter许可证归还、速率过载返回429、Redis unavailable按既有fail-closed。
- 完整历史B23/C26旧测试**以当前Rabbit实现重写/映射运行**，如果旧脚本依赖Kafka，不启动已退役Kafka容器充当“旧矩阵跑过”；拆为兼容等效场景或明确NOT_APPLICABLE原因，保留真实证据对照表。
**退出条件**：没有不被解释的INTEGRITY_GAP、无重复付款/扣额/开通/超卖，真实broker/DB/Redis故障可恢复；原用户资源不动。
**证据**：`v12-d2-four-rabbit-recovery.json`、`v12-d2-legacy-regressions.json`、`v12-d2-identity-rollback.json`。

## D3｜100000+ MySQL样本、游标索引与慢SQL优化（旧V11-D合并）

- 新脚本`scripts/v12-d-sql-benchmark.py`（建议）只在D owner新隔离DB生成固定seed的≥100000行合法脱敏Ledger/Outbox/审计模拟数据并建立外键合法的APP/API/UTC月关系；生成数据与真实业务演示另隔离，防压测样本污染业务对账。
- 审查旧`fg_quota_ledger/fg_outbox_event/fg_usage_daily`、新`fg_api_quota_ledger/fg_api_usage_outbox/fg_api_usage_daily`、`fg_audit_log`、订单/活动/PromoOutbox后台按应用/API/期间/日期/状态的有界查询，深页不能`OFFSET 100000`，使用`(created_at,id)`/稳定游标，默认limit<=100或对应业务更小值，重复时间戳不能漏或重复。
- 用真实`EXPLAIN ANALYZE`、rows examined、query elapsed、索引/写入开销对比baseline与最小必要索引，运行相同SQL和资源/数据/参数；对旧MySQL Schema不任意改列或Drop表。必要新增索引通过仅D隔离库的幂等迁移验证并确保旧业务行为不变。
- 完成后把生产环境**推荐但未执行**的增量SQL与备份/回退说明写入中文runbook。
**证据**：`v12-d3-sql-100k.json`、`docs/acceptance/evidence/v12-d-sql-benchmark.csv`等实际生成之后再提交；若资源不足NOT_REACHED并写采样上限与原因，不能用10k冒充100k。

## D4｜Gateway DIRECT/PROXY/GOVERNED/METERED四组公平性能实测

- 新`scripts/v12-d-gateway-benchmark.py` + 相同固定Demo fast/slow/compute/DB-heavy样本，提前warmup、每组至少两轮/固定客户端inflight/计划到达/运行时长；A DIRECT上游、B PROXY、C GOVERNED(现有Redis rate+local concurrency+Resilience4j)、D METERED(C上额外MySQL quota)；METERED分别老V11_APP与新V12_APP_API，额度充足不自然耗尽。
- 真实指标：sent/planned/clientShed/completed/business2xx/429/503/504/upstream5xx、p50/p95/p99（区分成功和全部）、Gateway/Control/Demo CPU/RSS、GC（若可测）、DB连接池/锁等待/SQL、Redis命中、Rabbit lag、QuotaHop p95；双Gateway分节点采样。不能将Fast HTTP无配额吞吐当正式计量吞吐，不能把客户端shed丢掉。
- 根据瓶颈按需最小优化已经实现的`ReactiveTokenBucketLimiter`、`LocalConcurrencyLimiter`、`CircuitBreakerConfigRegistry`、MySQL/缓存，不引Nacos/Sentinel额外限流层；修改后同数据/同负载复测，无法证明改善就不宣传。
**证据**：`v12-d4-gateway-four-way.json`、原始结果CSV/曲线源数据与每轮命令，确认资源安全限额。

## D5｜C缓存与抢购更大负载/故障性能专项（C8欠账合并）

- 基线复用C的100库存120身份请求、1000公开热读作为历史，不当成本轮已测。新D隔离活动(100库存)分阶段：独立`1000→5000→20000 HTTP请求数`，并发24→64→128等按当前物理资源逐级上升，**不承诺20k QPS**；每轮生成足够且合法的匿名fixture/会话，用完整真实Control HTTP（不能单靠Redis EVAL模拟真实受理），报告完成QPS/p95/p99/409/429/503、每个APP/用户唯一、Stock`reserved+sold<=total`、Outbox/Consumed/订单/收据/Grant真实SQL。
- 冷/热API分类/列表/详情/offers、C活动公开投影与负缓存、并发回源/缓存失效/慢Reader覆盖、TTL抖动/Redis重启/故障降级有界DB负载，各自测量公开投影DB_LOAD**与全部MySQL查询数区别**，不可只算缓存Hit当数据库总降载。
- Redis Lua overbook拒绝、候选TTL欠卖后的有界epoch自动重建、并发两Control刷新活动、Redis卡顿下Readiness与payment不被错误抛弃；数据库和Rabbit重启结合抢购清理库存、消息迟到终态幂等。未达到20k请求明确`NOT_REACHED`，需要负责人在最后验收表里决定是否接受范围降低，而不是无限重复压测。
**证据**：`v12-d5-catalog-promo-load.json`+故障时序、MySQL核账/CPU/RSS/Redis metrics。

## D6｜观测、运营诊断与前端完整可用性

- Console补齐四Rabbit链NEW/IN_FLIGHT/SENT/Consumed/Failed/Permanent/RETRY_EXHAUSTED（区别Broker Ready和业务已消费）、poison/lag/最后错误及原事件受控补投；活动MySQL total/reserved/sold vs Redis epoch/softRemaining、缓存Hit/Miss/DB_LOAD/InvalidationFailed、Gateway route保护状态/版本、计量日对账；真实API返回未知/NOT_READY时要明示UNKNOWN，不造“全部正常”。
- 记录低基数Micrometer counter/timer或现有结构即可，只有有出处的实际数据才展示；每个异常可定位到运营处理动作（查看权威事件/订单、下一次可重排时刻、需要管理员确认），不允许前端展示完整凭证、密码、支付模拟内部字段。缓存故障/活跃请求排队不引大而全平台。
- Portal/Console同步个人应用、API目录、免费试用、正价套餐、抢购排队→订单→付款→权益与余额、超时/取消/退款、ADMIN发布/暂停/重建/Poison、当前月/下月；页面真正“工作台”式清晰导航，不加营销标语/技术指标堆积；400/401/403/404/409/429/503/504都有人类可理解错误及可执行下一步。
- 用真实Chromium分别1280×和375×视口检验`document.scrollWidth<=innerWidth`、键盘Tab/Enter/Escape、弹窗焦点、表单无需人工输入数据库ID/JSON/Redis key、长列表和低速网络、不影响旧免费/订单路径；Codex自动化浏览器不是负责人本人验收。
**证据**：`v12-d6-product-and-diagnostics.json`、脱敏截图与真实HTTP/角色请求矩阵。

## D7｜完整回归、中文交付包、安全与部署演练

- 一次性运行项目全部Java17 Reactor`mvn -B -ntp verify`、Node test entrypoints（含Console/Portal）、Python unittest和实际HTTP脚本，范围：V1旧动态路由/熔断/保护、V11旧开发者应用/套餐/Free5、M Rabbit-only旧日报、A免费、B订单模拟付款/退款、C活动库存与所有角色/越权、D SQL/性能/故障；缺失用例标NOT_EXECUTED，不借旧历史SHA充数。
- 新隔离环境从空卷仅通过中文步骤建MySQL/A/B/M/C表（C有安全ALTER）、启动4个Rabbit queue逻辑链和2Control/2Gateway、停止→持卷重启→重启后真实Gateway/订单/消息健康，检验默认OFF和不存在C表时旧B/控制面可用；命令写成`scripts/v12-d-final-smoke.py`（建议）与中文runbook。`flowgate-local`常驻环境完全不动，最终不自动切换生产。
- 权限矩阵ADMIN/OPERATOR/VIEWER/PORTAL_USER/APP_CALLER/CALLER、一次性Token日志/截图脱敏、Header伪造/跨工作区ID、优惠价篡改/库存重试、注入、异常HTTP与SSRF防护、配置快照签名/只读未知状态、Quorum权限；原密码或密钥不出现在PR。
- 更新根README、AGENTS、`.env.example`、中文`docs/guides/v12-d-final-runbook.md`和现有Portal/Console使用指南，给出故障紧急开关/回滚/隐患清单，确保旧Kafka仅历史归档、当前Maven/Compose没有运行时Kafka；**不得在用户root.env自动开启C或执行ALTER**。
**证据**：`v12-d7-full-regression.json`、完整依赖树/启动命令/真实退出码、回归矩阵及脱敏文件哈希。

## D8｜最终独立复审、用户真人验收与Git收口（最后阶段终点）

- 生成`docs/progress/v12-d-final-implementation.md`（D0～D8真实进度/首次失败/修复/未执行/来源SHA）、`docs/acceptance/v12-final-results.md`（无假结果的完整总验收）、`docs/reviews/v12-d-final-source-review.md`（实际源码/事务/安全/性能审查）；必要新runbook以真实修复后的实现为准。没有跑/测试失败的格子必须用`NOT_EXECUTED/FAILED/NOT_REACHED/PENDING_OWNER`，不填空白PASS。
- 在同一个`feat/v12-d-final-delivery`分支、同一个Draft PR中交付所有变动，报告最终文件树、base/HEAD、真实commit SHA、证据与原始脱敏数据。检查#20/#21树重叠，并给**用户审批的安全合并/关闭计划**：已含完整C文件树的一个PR有真实差异，另一个冗余不再重复合并。未经批准不直接修旧branch/PR base/head，不自动合并main/tag或操作生产。
- 最后向用户发**可在浏览器实际执行**的验收清单：ADMIN发布V12 API商品→Portal用户App/Key/免费/正价/促销→Rabbits→模拟付款→2 Gateway→余额/日报/账本→退款/重复请求/对账→后台异常页；提供1280与375真人手测入口和保护性启动停止命令。**只有负责人真正体验后在独立记录中确认**，才允许标全项目`ACCEPTED`；若缺D4/D5压测，必须写清是`CONDITIONALLY_ACCEPTED`/待补范围，不能写“20k QPS achieved”。
- 交付后的默认动作是停本轮自有隔离测试容器并保留卷/日志，常驻`flowgate-local`不动，PR保持Draft，供ChatGPT/用户最后核查。**不创建V12-E、Nacos/Sentinel支线、生产部署任务或下一业务阶段。**

## 唯一进度表（当前均未实施，不允许先写PASS）

| 包 | 内容 | 初始状态 | 必交证据 |
|---|---|---|---|
| D0 | Git/版本/安全环境与完整基线 | NOT_STARTED | `v12-d0-preflight.json` |
| D1 | Outbox重试耗尽P1和运营恢复 | NOT_STARTED | `v12-d1-outbox-recovery.json` |
| D2 | B/M/C/旧V11 Rabbit-only四链可靠性与历史矩阵 | NOT_STARTED | `v12-d2-four-rabbit-recovery.json` |
| D3 | ≥100k SQL/游标与真实优化对照 | NOT_STARTED | `v12-d3-sql-100k.json` |
| D4 | Gateway四组公平性能 | NOT_STARTED | `v12-d4-gateway-four-way.json` |
| D5 | Redis缓存与抢购1k→5k→20k请求阶梯 | NOT_STARTED | `v12-d5-catalog-promo-load.json` |
| D6 | 运营观测及Portal/Console完成度 | NOT_STARTED | `v12-d6-product-and-diagnostics.json` |
| D7 | Java/Node/Python/隔离端到端/安全/中文部署 | NOT_STARTED | `v12-d7-full-regression.json` |
| D8 | 独立审核/最终报告/负责人真人验收/PR收口 | NOT_STARTED | `v12-d-final-implementation.md`、`docs/acceptance/v12-final-results.md` |

**最终交付只有一个“完成/未完成/条件接受”结论，所有技术和产品欠账都在本阶段处理。** 任何P1失败或权威数据缺口未修不得标完成；资源瓶颈导致压测档位降级也必须有可复验的真实数据和负责人明确接受；自动截图不等于人工验收。
