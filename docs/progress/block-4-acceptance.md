# 功能块 4｜最终综合验收、三组性能对照与 FlowGate V1 交付（实施任务 V1.0）

> 2026-11-06。负责人已明确授权“设计并完成功能4”。**本阶段不是再新增一套业务，而是将已经实现的 Block0–3 作为整体，提供可重复的验证、证据、缺陷收口、前端说明和主线交付条件。**
> 代码分支：`test/block4-final-acceptance`（从远端 `feat/block3-resilience-observability` 创建），不从旧 `main` 开始。
> 阶段状态：**验收工具、执行规范和 Codex 交接可在仓库预先交付；本次 ChatGPT 不能访问负责人的本地 Java17/Docker 环境，完整真实验收尚未运行。负责人真实浏览器体验和 PR 合并不能被自动化或文档替代。**
> 权威参考：`docs/design/product-console-v1.md`（新增P0产品化要求）、`docs/acceptance/final-acceptance-plan.md`、`docs/progress/final-acceptance.md`、`docs/reviews/block-3-static-review-2026-11-06.md`。

## 0. 负责人新增的 V1 产品化前置要求（2026-11-06）

**V1 必须有一个非开发者看得懂的真正管理后台。** 负责人明确反馈旧 `/test-console/` 虽有所有接口按钮和14章说明，但依然“乱七八糟”，不足以作为产品 UI。此反馈优先于此前“原生简易测试前端足够”的最低标准：

- **正式产品入口** `/console/`：登录、业务工作台、服务/API/路由列表与创建编辑、流量治理、故障保护、发布中心、Caller、监控审计；中文业务表单和清晰反馈，主要管理不要求手写JSON、内部数字ID或理解HTTP测试流程。
- **开发/验收入口** `/test-console/`：旧 HTTP 请求、故障注入和测试工具保留，不删除、不重写成产品首页；仅在产品左下“开发工具”提供次级链接。
- 原生 HTML/CSS/JavaScript ES modules，跟随 Control 静态托管；无须新框架或中间件，UI 要真正达到产品可用性标准，不能只是原页面换颜色/侧边栏。
- 权威详细契约：`docs/design/product-console-v1.md`；**当前唯一应先交给本地 Codex 的任务**：`CODEX_PRODUCT_CONSOLE_PROMPT.md`。完成产品后台源码、真实功能/定向测试和负责人真实浏览器反馈收口后，才继续下面 B4-00..06 的正式全量回归与长时间三组性能。
- 若产品UI需要显示已有策略绑定，现有Control缺少读取绑定API，允许**最小新增只读查询**（已有 mapper/表及 RBAC）；不允许为了UI伪造业务状态、随意新增删除API或改Gateway内核。
- 本次 ChatGPT 已交付产品UI设计及 Codex 任务合同，**尚未完成全产品前端编码和人工可用性验收**。不得把文档写成“重设计已上线”。

按顺序完成：`PC-00` API核对 → `PC-01` 产品登录与工作台 → `PC-02` 目录 → `PC-03` 策略 → `PC-04` 发布/Caller/监控 → `PC-05` 响应式与可访问性 → `PC-06` 定向测试/指南/提交。**产品化是Block4的P0门槛，不另开新功能块，也不提前合并main。**

## 1. 最小交付目标

用现有 Java17 + Maven + Python3 标准库 + Node 内置能力 + 本机 Docker 的隔离环境证明：

1. **正确**：从空数据库真实完成认证、目录、CAS 发布/回滚、路由代理、限速、并发、超时、熔断和恢复；旧版快照及 checksum 安全。
2. **稳定**：双网关共用 Redis 速率预算，但每网关并发许可独立；Control/Redis/Gateway 故障不造成未授权放行、错误版本报告或无限资源泄漏。
3. **性能代价可解释**：在同样的发送节奏和共享资源配置下，对照 A 直连、B Gateway 仅代理、C Gateway 启用治理。分别说明**正常业务成功吞吐与尾延迟**、429/503 拒绝、网关成本；不预设一定改善。
4. **产品可操作**：负责人能在新增 `/console/` 的正式产品管理后台中独立管理服务/API/路由、策略、发布、Caller及监控；旧 `/test-console/` 和离线 `guide.html` 只保留为开发验收工具，不在浏览器进行压测。
5. **交付可追溯**：保留真实命令、环境/版本、测试结果、原始脱敏样本、失败和修复记录。无阻断缺陷且负责人批准后才按 stacked PR 依赖顺序处理 main。

本块**不做**：新业务 B14、自适应算法、灰度、多租户、Kafka/Nacos/Sentinel/Grafana、为跑测试而改网关成 Servlet、生产级公网部署、未获批准的 Git 自动合并。

## 2. 固定源码与工具目录

| 文件 | 目的 | 状态 |
|---|---|---|
| `docs/progress/block-4-acceptance.md` | 本块唯一详细执行契约和真实进度 | 已建 |
| `docs/design/product-console-v1.md` | 产品级左侧导航/首页/CRUD/策略/发布/监控的强制产品化合同 | 已提交，待Codex实现 |
| `CODEX_PRODUCT_CONSOLE_PROMPT.md` | Block4正式验收前必须先执行的产品化任务 | 已提交，待Codex编码 |
| `CODEX_BLOCK4_PROMPT.md` | 本地 Codex 一次性实现/运行任务，不给自由改架构空间 | 已建 |
| `docs/acceptance/final-acceptance-plan.md` | 原有最终验收大纲，继续保留 | 已存在 |
| `docs/progress/final-acceptance.md` | 原有最终阶段摘要，链接本文件，不重复维护两套不同事实 | 已存在 |
| `scripts/block4-performance.py` | 隔离MySQL/Redis/双Gateway，三组固定到达速率和本地结果JSON | 已提交，尚未在真实JVM跑 |
| `scripts/test-block4-performance.py` | Python统计与固定请求节奏单测，防429/503误计成功 | 已提交，需本地Codex定向运行 |
| `scripts/block1-smoke.py`、`block2-smoke.py`、`block3-smoke.py` | 复用既有联调脚本，先看各自安全边界再执行 | 既有 |
| `flowgate-control/src/main/resources/static/test-console/acceptance.html` | 浏览器仅作验收说明与测试入口，**绝不伪造实时测试结果** | 本块同步 |
| `docs/acceptance/final-results.md` | 最终真实验收汇总，运行后由Codex填结果/证据；未运行项保留未运行 | 待实际运行 |

尽量不新建 Maven 模块、数据库表、服务进程或前端构建项目。只有最终验收真实发现阻断缺陷，才最小修正对应 Java/HTML/JS 并做回归，按 `fix(blockN):...` 说明根因。

## 3. Codex 必须执行的顺序与阶段门槛

### B4-00：环境、分支与数据安全

> **执行门槛：必须先完成本文件第0节PC-00..PC-06的产品化编码、定向测试与负责人可用性反馈收口。尚未完成时可仅核查环境，不启动后续正式三组负载。**

1. `git fetch origin`，核对 `test/block4-final-acceptance` 与远端 `feat/block3-resilience-observability` 的关系；保留所有本地未提交内容，不 `reset --hard`、`clean -fd`、强推或改 `main`。
2. 读取 `AGENTS.md`、本文件、`CODEX_BLOCK4_PROMPT.md`、`docs/acceptance/final-acceptance-plan.md`、两份测试台使用指南。固定本次 SHA、JDK17、Maven、OS、CPU/内存、Docker 版本/资源上限与运行时间；不能把 shell 默认 JDK21 误写成测试 JDK。
3. 不碰负责人运行中的 IDEA 应用、个人 MySQL/Redis、`.env` 或数据库卷；采用已核查的独立随机端口、独立容器与脚本自有临时目录。现有 `scripts/block1-smoke.py` 如依赖固定 MySQL 端口/socket 用户，不能直接在用户已有 MySQL 上运行，应在隔离环境改用安全替代，说明变更。
4. 若 Java/Docker 无法启动，记录真实错误，**停在未验收**；不把 Node/Python 静态测试当成 Java 联调成功。

### B4-01：完整回归（本阶段首次允许）

按安全检查结果在本地Java17环境中运行：

```bash
# 所有 Maven 模块；仅在确认没有IDE运行中的项目target/Jar依赖时可clean
mvn -B -ntp verify
node --check flowgate-control/src/main/resources/static/test-console/app.js
node scripts/block2-console-test.js
node scripts/block3-console-test.js
python3 scripts/build-console-guide.py --check
python3 scripts/test-block4-performance.py
python3 -m py_compile scripts/block4-performance.py
```

先记录 Maven 实际执行的 JUnit/集成测试总数、通过/失败/跳过；`verify` 成功不等于全部 HTTP/浏览器通过。因工作目录限制确须 `clean verify`，先保护IDE和未提交生成文件，不能擅自删除任何用户资产。

### B4-02：跨块真实 HTTP 和故障恢复

对隔离环境执行 `block2-smoke.py`、`block3-smoke.py`，并根据 Block1 当前动态路由行为补充或复核它们已覆盖：

- fast/echo/slow、Trace ID、encoded Path、正常/私有 Caller 401/403/200/吊销；
- MySQL CAS 409、多管理员并发发布、历史版本 checksum、PENDING 回滚后不执行新规则；
- Redis Lua 定点单Key、TTL/多维顺序、Caller 独立额度、两网关共享预算与本地许可独立；
- 正常/错误/取消/超时的本地许可归还，阻断请求确实不进入 Demo；
- TIMEOUT 504、CB CLOSED→OPEN→HALF_OPEN→CLOSED、故障恢复和 GET-only 503降级；
- Redis/Control 下线后 fail-closed，恢复重新上报，节点 ONLINE/STALE 和版本准确；
- Actuator只开放本地只读，Gateway不存在可写路由端点，响应/日志不泄漏密钥。

脚本应保留真实通过与失败计数。旧 Block0 静态通配路由脚本不是动态路由时代的可靠当前验证，不能把它的失败简单归因为回归缺陷或把其成功当现在的通过。

### B4-03：正式三组性能对照（本块新增代码）

`scripts/block4-performance.py` 在同一个独立 Lab 创建两个 Gateway、8 个 Demo Tomcat 工作线程、同一套 API，并以固定时间表执行：

- **DIRECT**：客户端直连 Demo 的 fast + slow；
- **PROXY**：相同请求量/节奏通过 Gateway；两类路由无启用流控；
- **GOVERNED**：通过 Gateway，给 slow 路由绑定容量 2/每秒补充 2 的 Redis 限速、本机并发上限 2；fast 无单独拒绝策略。应用版本后再发送；
- 按顺序重复至少 2 轮，各组含 3 秒预热 + 30 秒测量（可以先用 `--seconds 5 --repeats 1` 做脚本冒烟，**不能把它称正式性能结论**）；默认计划 fast 8/s + slow 16/s，客户端最大同时在途 48；
- 使用开放式固定到达时间表+在途上限，若客户端自身超载应明确记录 `CLIENT_SHED` 并标记公平性受损，绝不把它归为 Gateway 429/503；
- 计算各组业务成功 RPS、全部 HTTP 状态分布，fast 成功请求 P50/P95/P99 与总成功/全部响应延迟分别报告。样本少时注明 P99 不可信；如果启动高压导致CLIENT_SHED，应降低**全部三组**目标发送量后重跑；
- 记录 Demo 实际在途峰值、Gateway/Demo CPU 和 RSS、Redis CPU/内存（如现有工具安全可读），不要直接把ps进程累计百分比当稳态CPU，无法测到的写未测；
- 单网关承载性能负载，第二个Gateway用于独立的跨节点正确性测试；不能将服务级并发当成两台网关的全局限额。

执行：

```bash
# 验证脚本是否能安全启动/退出；短实验不能冒充正式样本
python3 scripts/block4-performance.py --seconds 5 --warmup 0 --repeats 1 \
  --output .local/acceptance/block4-dryrun.json

# 正式方案：每组三十秒，三组、两轮，结果本地保存、不覆盖已有文件
python3 scripts/block4-performance.py --seconds 30 --warmup 3 --repeats 2 \
  --output .local/acceptance/block4-performance-run1.json
```

Python 写入本地 `.local/acceptance/`，不会覆盖旧证据，JVM 和容器在 `finally` 中只清理本次自建的资源；任何异常生成 `passed:false` 的部分记录，**不能把此文件上传公开GitHub当作已经验收的证据**。需要保留原始脱敏样本时，由 Codex 先验证内容安全，再遵从用户授权选择提交小型摘要及必要样本。

注意：三组是在同一次本地进程环境中依次完成，环境会随运行时间变化；必须记录组别顺序、样本量、资源环境。后续如做顺序轮换，需明确是否真实生效，不得默默调整数据。

### B4-04：长期容量边界和异常时许可

对官方 Resilience4j 注册表按不同配置 revision 连续变更若干次（建议 20 次以内的**有界**小实验），记录真实 breaker 实例数/指标标签、Demo/Gateway 内存采样与是否能清理历史状态；不可为了修复实例增长而改写历史快照或误杀在途旧请求。若无法安全清理，标记 V1 的已知技术限制并说明可接受场景，不把“短实验通过”写成“无限长期稳定”。

逐项记录 Gateway 客户端取消时许可是否释放、Servlet 下游可能仍在执行以及上游网络失败与应用错误的区别。

### B4-05：负责人真实浏览器测试

> **主验收对象已改为产品后台 `/console/`**。旧 `/test-console/` 仅用于技术异常/HTTP 调试验证。必须按 `docs/design/product-console-v1.md` 逐页验证正常业务流程，不得将旧页使用指南通过视为新产品可用。

负责人需要亲自打开 `http://127.0.0.1:8081/console/` 完成产品管理任务，并按需通过底部开发工具访问 `http://127.0.0.1:8081/test-console/` 和离线 `guide.html`：
1. 不看源码完成首屏“登录→新建或读取目录→发布并确认节点→fast 200/echo 201”；
2. 使用限速/并发、超时/熔断/降级表单真实看到 429/503/504，对照节点实际状态；
3. 普通用户、VIEWER/OPERATOR/ADMIN 权限差异和 Caller 授权/吊销；409 版本冲突、回滚和原有策略绑定编辑；
4. 1280px 桌面和窄屏、Tab 焦点、字段说明、离线帮助、结果摘要脱敏与临时 Token 清空；
5. “验收说明”页面只展示如何运行脚本和证据状态说明，不能读取用户本机私有测试报告或硬编码“压测通过”。

本块 Codex **不得操作负责人电脑界面**，只能保留“人工验收待执行”；负责人回报的问题在 Block4 分支进行必要修复、前端与指南同步，复跑受影响范围。

### B4-06：最终交付审计与 Git

检查 README 与实际命令/模块/依赖/端口一致、第三方许可归属、中文注释（长段 `// 1.`、`// 2.`、`// 3.`）；无新增 Kafka、Nacos、Sentinel、测试服务器或 npm 工程。保持 `main` 不变，PR #1→#2→#3→#4→Block4 最终 PR 的叠加关系；最终合并需负责人**单独明确批准**，不得擅自进入 main。

在 `docs/acceptance/final-results.md` 记录：提交 SHA、环境摘要、每条实际命令及退出码、JUnit 总数、HTTP 状态和节点/熔断行为、三组性能的样本量和公平性限制、资源成本、人工体验结果、未覆盖项目/剩余风险。未执行写“未执行”，不能勾选。

## 4. Block4 合格/不合格/未完成判定

| 范围 | PASS 条件 | 目前 |
|---|---|---|
| 构建/全量JUnit | JDK17完整 verify，全部实际用例无失败 | 未执行 |
| 模块实链路 | B01–B12 核心HTTP状态和权限/旧快照正确，失败原因可追溯 | 未执行本块统一回归 |
| 3组性能 | DIRECT/PROXY/GOVERNED各至少2轮，完整数据且没有无法解释的客户端丢负载 | 脚本已编写，未运行真实JVM |
| 稳定性/容灾 | 双网关/Redis/Control/CB/许可边界有完整真实记录 | 未执行本块统一实验 |
| 安全与文档 | 无敏感信息，低基数指标，README和使用指南准确 | 待最终复审 |
| 产品化页面 | `/console/` 真正实现完整业务可操作流程、响应式、角色、就地反馈 | 设计已提交；未编码/未验收 |
| 页面可用性 | 负责人在新产品后台手动验收、缺陷修复 | 未执行 |
| Git正式发布 | PR顺序审核，负责人明确授权合并 main/tag | 未授权 |

**Block4 的“设计/工具交付完成”与“项目 V1 通过最终验收”是两件不同的事。只有上述各项实际通过且负责人确认，才可标记 FlowGate V1 可交付。**

## 5. 本块进度回填区

| 项目 | 实际状态 |
|---|---|
| 分支 | `test/block4-final-acceptance`，来自远端Block3最新代码 |
| 任务书/Codex提示 | Block4任务书与Codex提示已提交；新增产品化P0设计和专用Codex任务已提交 |
| 三组性能脚本/纯统计单测 | 已在仓库新增；ChatGPT 在隔离Python环境对原型做过语法和3项纯统计测试，**GitHub提交版本仍需Codex本地复测** |
| 新产品后台 `/console/` | 已明确P0架构与功能要求，尚未由Codex编码或真实体验；原 `/test-console/` 保留 |
| Java全量测试 | 未执行 |
| 既有 Block1/2/3 集成脚本统一运行 | 未执行 |
| 三组真实HTTP性能实验 | 未执行 |
| CB高频修订/资源容量实验 | 未执行 |
| 负责人真实浏览器/权限体验 | 未执行 |
| 综合验收结果与main合并 | 未完成/未授权 |

**每轮完成后，Codex在本节后面追加真实SHA、命令与通过/失败数；不得覆盖本文件里写明的未执行事实。**

### 5.1 2026-11-06 Codex本机真实执行（准备阶段表保留历史）

实际报告：[final-results.md](../acceptance/final-results.md)，证据：`docs/progress/evidence/block4-functional.json`、`block4-boundaries.json`、`block4-performance-summary.json`。本阶段功能分支`test/block4-final-acceptance`，任务基线`df00937a8403be4912101d2b0344707e2ce5e290`，脚本提交`d59acf94674e0fe5017e552568e3a4de5dee3986`；后续提交同步本节/报告/页面与最终Demo RSS采样。仅新增验收脚本、资源统计及文档，没有改Java业务代码、运行依赖或schema。

| 项目 | 本轮实际结果 |
|---|---|
| 全工程verify | Java17.0.18/Maven3.9.13，Contract7+Control1+Gateway22+Demo4=34通过，0失败/错误/跳过 |
| 首次verify失败 | 沙箱阻止Mockito self-attach导致Control1 error；正常本机权限复跑退出0，没有跳测试冒充通过 |
| Node/Python/指南 | 两套Node模拟DOM、JS语法、HTML本地链接/50字段/无脚本验收页、14章指南一致性、Python4项统计/CPU差值、py_compile均通过 |
| Block2/3统一HTTP | 块2四组断言含真实自然TTL通过；块3本轮191条passed=true，保留旧192条历史证据，不覆盖旧结果 |
| 补充Block0/1/2/容量 | 最终361条passed=true，身份/代理/TTL/撤销/并发CAS/五维顺序/真实取消与网络失败/20次revision/节点STALE→ONLINE；旧块1固定socket脚本未直接运行 |
| 补充脚本失败修正 | 测试夹具1500ms全局超时与合法2000ms请求冲突；50%阈值已OPEN却误期望再次500；均修正实验配置/断言，失败本机记录保留 |
| 默认性能短冒烟 | 5秒8+16/s，DIRECT/PROXY各20个CLIENT_SHED，脚本退出0但passed=false，不用于公平性能结论 |
| 校准与正式性能 | 三组统一8+8/s；5秒校准passed=true；正式30秒+3秒预热×三组×两轮=2880条，CLIENT_SHED/NETWORK_ERROR均0，passed=true |
| 性能实际 | 每组fast240/240成功；PROXY fast P95约299–317ms，GOVERNED约10.8–11.6ms；治理拒绝slow，全部业务成功RPS由16降至约9.4（发送窗口口径），并非总体吞吐提高 |
| 资源与容量 | CPU累计秒差与RSS/Redis内存前后值已采集；完整GC后CB实例1→21，旧revision未清理；适用于低频本机配置演示，不能承诺无界长期稳定 |
| 前端与文档 | 原业务操作保留；验收说明仅说明，不读私有报告/执行压测；同步校准命令及指南。Control静态资源打包退出0（明确skipTests），Jar五项资源与源文件一致 |
| 人工/正式交付 | 真人浏览器、ChatGPT复审、CI、生产SLA/部署、main合并/tag未执行/未授权；当前不能宣称V1最终通过 |

实际命令（除首次verify和两次补充实验失败外，下面各项退出0；完整说明及负载数字见报告）：

```bash
source .env
mvn -B -ntp verify
node --check flowgate-control/src/main/resources/static/test-console/app.js
node scripts/block2-console-test.js
node scripts/block3-console-test.js
python3 scripts/build-console-guide.py --check
python3 scripts/test-block4-performance.py
PYTHONPYCACHEPREFIX=/tmp/flowgate-block4-pycache python3 -m py_compile scripts/block4-performance.py scripts/block4-boundaries.py
python3 scripts/block2-smoke.py
python3 scripts/block3-smoke.py
python3 scripts/block4-boundaries.py
python3 scripts/block4-performance.py --seconds 5 --warmup 0 --repeats 1 --output .local/acceptance/block4-dryrun.json
python3 scripts/block4-performance.py --seconds 5 --warmup 0 --repeats 1 --fast-rps 8 --slow-rps 8 --output .local/acceptance/block4-dryrun-balanced.json
python3 scripts/block4-performance.py --seconds 30 --warmup 3 --repeats 2 --fast-rps 8 --slow-rps 8 --output .local/acceptance/block4-performance-run1.json
mvn -B -ntp -pl flowgate-control -am package -DskipTests
git diff --check
```

原始JSON/日志保留本机`.local/acceptance/`，脱敏小摘要才入库。新增Python关键流程中文编号注释已检查，无新增Plus复制；既有Lua来源与许可保留。不修改.env、不停止负责人IDE进程/持久化容器、不使用电脑界面。Git最终结果以本节随后记录为准。

### 5.2 执行期间的远端要求更新

收尾fetch发现远端df00937到823556f新增产品后台P0门槛。5.1是原任务书基线的历史实跑事实，不代表新版Block4完成。新/console/尚未实现，不以旧测试台/性能替代产品验收；保留原证据，合入新要求后先执行PC-00..06，不再追加正式长对照。

### 5.3 新产品后台P0实施与定向验证

按合入后的产品合同执行PC-00..06，代码提交`0758787`；完整任务式指南`docs/guides/product-console-guide.md`及`/console/guide.html`已同步。产品入口`http://127.0.0.1:8081/console/`，根路径自动跳转；旧`/test-console/`保留开发功能。

- 已实现：独立登录/闭包会话、角色禁用提示、工作台真实数量/依赖/版本/节点；服务/API/路由搜索/创建/编辑完整PUT；速率/并发/超时/熔断独立表单、真实绑定只读、GET降级限制、草稿提示；发布CAS二次确认、409不重试、历史摘要/回滚；Caller一次性内存回执和吊销；真实节点/低基数指标/审计分页；帮助、空状态、失败重读、dialog焦点/Escape、响应式菜单/表格。
- 后端最小增量：`GET /admin/policies/{id}/bindings`复用既有mapper/RBAC；DTO只返回bindingId/policyId/targetType/targetId/targetLabel/targetRouteId。缺失404，无新写操作/表/模块/运行依赖。新增Java2个定向用例检查真实目标、空绑定/缺失和不暴露Caller秘密。
- 产品限制明确：没有差异预览/逐对象发布状态/完整历史快照查询，不把目录标成已上线；没有Caller全量列表，规则新绑定Caller只能选本次签发记录，吊销已有Caller需已知签发编号；无绑定移动/删除。绑定读取失败时禁止编辑，部分保存失败不重复创建规则。

实际命令与结果：

```bash
source .env
mvn -B -ntp -pl flowgate-control -am test package          # 退出0，Contract7/Control3共10通过，无失败/跳过
node scripts/product-console-test.mjs                    # 退出0，真实ES模块+模拟DOM两组通过
node scripts/block2-console-test.js                       # 退出0
node scripts/block3-console-test.js                       # 退出0
node --check flowgate-control/src/main/resources/static/console/app.js
# api/ui/catalog/governance/operations.js也分别node --check通过
python3 scripts/build-console-guide.py --product --check  # 退出0，7节产品指南
python3 scripts/build-console-guide.py --check            # 退出0，旧14章保留
PYTHONPYCACHEPREFIX=/tmp/flowgate-product-pycache python3 -m py_compile scripts/product-console-smoke.py scripts/build-console-guide.py
python3 scripts/product-console-smoke.py                  # 最终退出0，独占Lab+真实产品JS实际HTTP
mvn -B -ntp -pl flowgate-control -am package -DskipTests    # 最终静态资源更新，退出0，明确不是重跑Java测试
git diff --check
```

首次产品HTTP返回/console/404，补Spring目录forward后复测通过；首次Node命令使用不支持的选项/夹具健康404误期望已修正，均未改生产语义掩盖失败。本阶段未操作电脑界面、未重跑完整工程/正式长压测、未停止用户应用、未修改.env或持久化容器数据。脚本finally仅清理自有资源。产品/开发两套入口及离线帮助都真实200，最终HTTP状态数和脱敏文件见本节随后补记。

中文关键路径注释和长逻辑编号已检查；无innerHTML注入/外部CDN/凭证持久化。负责人1280px/375px/Tab/角色实际体验**未执行**，应先在产品页反馈并修复，之后才能按新门槛继续正式整体验收；旧5.1结果仅是旧基线证据。

产品最终脱敏HTTP证据：`docs/progress/evidence/product-console-smoke.json`，passed=true。Lab初始化/静态/边界31条（200×20、201×9、401×1、404×1），真实产品JS模块33条（200×18、201×6、204×4、401×1、403×2、404×1、409×1），共64条已记录请求。Node子进程用stdin传随机凭证，记录不包含密码/Token/Header/Body。Node用于读取回执的辅助登录请求不在这64条计数内，不将此数字宣称为全部网络包数量。产品Root/目录入口、9项静态资源、旧工具和旧指南均可HTTP访问。

最终补充本地HTMLParser资源链接/手册锚点/静态label与ID、6个JS模块语法、安全渲染/无持久化检查均通过。页面视觉与真实浏览器交互仍未代验。产品化后只进行了上述定向验证，旧基线长性能记录不作为新产品最终通过。**当前状态：产品编码与定向验证完成，等待ChatGPT和项目负责人复审及真实浏览器验收；功能块4整体验收尚未完成。**

### 5.4 本轮Git交付实际结果

原基线脚本`d59acf9`、证据`5136035`；安全合入产品化远端要求的merge为`af28016`，未重写任何已共享历史。产品主实现`0758787`、产品测试/真实证据/指南状态提交`7e94534`。

执行且仅执行`git push origin test/block4-final-acceptance`，GitHub返回：`Permission to Felix0410/FlowGate.git denied to Felix0410`，HTTP403，**本轮新提交未推送成功**。最近fetch远端仍`823556f8ce744707bb02ace4ddf813053d25a1fe`，不含本轮/console/实现。没有改推main、强推、变更Git凭据或用不同历史覆盖远端。保留本地全部提交，远端产品代码交付受写权限阻塞；不声称已有包含本次产品实现的新PR/CI。

代码已在本地打包。使用时在当前分支重新启动Control，再打开`http://127.0.0.1:8081/console/`；Demo/Gateway/数据库仍按原配置。没有为展示新版页面自动启动或重启负责人应用。当前只等待复审/负责人体验，功能块4整体验收尚未完成。


### 5.5 2026-11-06 ChatGPT远端代码复审（更新旧push403的现时状态）

- **远端当前事实已改变**：GitHub的 `test/block4-final-acceptance` 现在包含产品主实现 `075878747ea45590cefda41f6fcd185cc163afa2`、产品后续证据与源码、独立 `/console/`、只读绑定GET、Node/Java/Python脚本。此轮复审开始时远端SHA=`2ac5998c2aa55b1a66efbbd012035474c6ac652b`，PR #5为Draft、base仍为Block3分支。第5.4节的Git push HTTP403是Codex**当时的真实失败记录**，不能删除或当作目前代码尚未推远端的结论。
- 静态审核已覆盖 `console/{index,style,api,app,ui,catalog,governance,operations}.js/html/css`、Control绑定只读API及RBAC、JUnit和Node脚本、`product-console-smoke.json`。实际证据为主Lab 31+产品JS HTTP 33 = **64条**且passed=true。Codex的产品模块10项JUnit、Node/HTTP通过与第5.3节吻合，ChatGPT本轮没有自行运行本地Maven/Docker或实际浏览器。
- 用户关心的“是不是产品”：已实现独立登录、业务导航、列表/创建/编辑、四类策略、发布中心、Caller凭证、监控审计和中文任务指南，不是旧测试台改配色。仍需负责人**真实浏览器**检查桌面1280px/手机375px、键盘/弹窗、第一次接入流程和中文易读性，不能仅凭JS源/CSS/模拟DOM宣告体验通过。
- **P1产品使用缺口**：当前 `AdminAuthController` 没有Caller列表GET，`operations.js`只能显示本次内存签发记录；刷新后无法方便管理历史Caller，旧凭证吊销需人工编号，CALLER规则也无法方便选择已存在调用方。建议最小新增ADMIN-only安全摘要列表（绝不泄露Token/Hash）与真实列表/按行吊销；负责人在体验阶段决定是否列为V1必须修复的阻断。
- **P2边界**：运行指标只读取默认本机 `127.0.0.1:8080`（非集群聚合），已有Resilience4j历史revision实例无界积累需保留容量限制说明；产品改造后的**最终全工程verify和正式整体验收**未重新执行，旧第5.1节34JUnit与六组性能是前产品基线。
- **审核报告**：`docs/reviews/product-console-static-review-2026-11-06.md`。当前结论为**源码实现与定向验证可交真人体验；Block4/V1最终验收未完成，全部PR保持Draft，不合并main**。
