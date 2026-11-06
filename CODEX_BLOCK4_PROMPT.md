# FlowGate 功能块 4｜交给本地 Codex 的实际执行提示词

> 负责人已授权功能块4，并新增了**V1必须先完成真正产品级前端**的P0要求。目前唯一优先任务是 `CODEX_PRODUCT_CONSOLE_PROMPT.md` + `docs/design/product-console-v1.md` 的产品后台完整编码与可用性收口；完成后才执行本文统一回归与三组性能对照。不要新增网关业务。
> GitHub 已创建 `test/block4-final-acceptance`，其基础是 `feat/block3-resilience-observability` 实现分支。详尽契约为 `docs/progress/block-4-acceptance.md`，此文件只是执行顺序，遇冲突以前者为准。

## 0. 产品UI前置门槛（新增最高优先级）

- **先停止执行正式三组性能负载**，直接阅读 `CODEX_PRODUCT_CONSOLE_PROMPT.md` 和 `docs/design/product-console-v1.md`，完成 `/console/` 产品级登录、工作台、真实服务/API/路由 CRUD、治理策略、发布、Caller、监控及响应式；保留 `/test-console/` 作为开发工具。
- 产品UI不是修旧 CSS 或补说明，必须做到非开发者可独立完成正常业务操作；无假指标/假在线/假发布，不要求填写 JSON 或数据库内部 ID。
- 新产品后台的定向测试、真实 Control/Gateway 小联调与用户浏览器可用性反馈收口后，才继续下面 B4-00..06 的全量回归与长性能实验。

## 1. 立即处理仓库与环境安全

1. 在本地 FlowGate 执行 `pwd`、`git status -sb`、`git remote -v`、`git log -1 --oneline`，保护所有未提交修改；禁止 `reset --hard`、`clean -fd`、强推、清空Docker数据卷或覆盖 `.env`。
2. `git fetch origin`，先判断是否已存在本地同名分支；没有时安全执行：
   `git switch -c test/block4-final-acceptance --track origin/test/block4-final-acceptance`。
   不要从旧 main 新建，不要改 PR #1–4 的目标分支。
3. 阅读 `AGENTS.md` → `docs/progress/block-4-acceptance.md` → `docs/acceptance/final-acceptance-plan.md` → `docs/progress/final-acceptance.md` → `docs/guides/test-console-guide.md` → `docs/guides/block3-fault-testing.md`。
4. 明确当前环境的 JDK17 路径、Maven/Docker 可用性、已有 IDE/容器/端口占用和隔离计划。本项目三个 JVM 的 Java17 不得混用 shell 默认 Java21；安全验证前不要运行可能影响个人数据库的老脚本。

## 2. 执行顺序：逐步记录，不要自行改需求

**B4-00** 固定基线和环境：Git SHA、实际 JDK、CPU/内存、MySQL/Redis隔离容器、两Gateway、Demo线程上限；记录 `main` 和 PR 当前状态。

**B4-01** 全工程回归：
- 在不破坏用户IDE服务和工作树的情况下运行 `mvn -B -ntp verify`；记录各模块JUnit实际成功/失败/跳过；
- `node --check .../app.js`，`node scripts/block2-console-test.js`，`node scripts/block3-console-test.js`，`python3 scripts/build-console-guide.py --check`；
- `python3 scripts/test-block4-performance.py`、`python3 -m py_compile scripts/block4-performance.py`；
- 如果本阶段文件/脚本有实际编译/逻辑错误，做最小修正并重跑。不要用跳过测试代替通过。

**B4-02** 真实隔离 HTTP 总体回归：复用 `block2-smoke.py` 和 `block3-smoke.py`，核对现有 `block1-smoke.py` 对固定端口和数据库的要求，不能随意对本机用户数据库执行。覆盖代理、认证、发布回滚、Lua、两Gateway、熔断降级、Redis/Control/无快照故障、安全与Actuator边界。

**B4-03** 使用刚提交的 `scripts/block4-performance.py`：
- 先运行 `--seconds 5 --warmup 0 --repeats 1 --output .local/acceptance/block4-dryrun.json`，实证独立容器安全、3组与结果字段有效；
- 再使用 **30s/组 + 3s预热 × 3组 × 2轮** 作为V1正式本机对照，输出到新的 `.local/acceptance/block4-performance-run1.json`，不可覆盖旧证据；
- 分别报告 DIRECT（直连）、PROXY（只代理）、GOVERNED（只控制slow）的**业务成功RPS、fast成功P95/P99、HTTP429/503数量、总成功率和下游在途数**；发现CLIENT_SHED、过低成功样本或不公平资源差异必须坦诚报告，不能计算虚构的提升比例；
- 记录真实 CPU/RSS/Redis消耗，无法观测写“未采集”。短压测结果并非生产SLA。

**B4-04** 对快照版本、取消/迟到缓存、Control/Redis故障恢复、Resilience4j不同revision的实例累计和内存、在途许可终止进行有界容量检查。发现缺陷用最小 `fix(blockN)` 处理，回归当前及直接受影响模块。

**B4-05** 同步 `/test-console/` 业务页面的验收说明/帮助，仅用于用户操作入口和真实状态，不把Python压测搬进浏览器，也不允许凭证上报。页面中的 Block0/1/2/3 全功能必须保留，`guide.html` 继续可离线使用。负责人真实浏览器的1280px/窄屏/键盘/角色反馈只写“待人工”，不得以Node模拟DOM冒充通过。

**B4-06** 写 `docs/acceptance/final-results.md` 的真实验收结果，带命令、退出码、JDK、总数、原始脱敏文件路径、样本/公平性、已知故障与修复SHA；同步 `docs/progress/block-4-acceptance.md` 最末进度区、`docs/progress/final-acceptance.md`、README、前端状态说明。未执行的项目写“未执行”。

## 3. 绝对禁止

- 不删除个人 Docker/数据库卷，不运行全局 `docker prune`，不更改凭据，不覆盖用户本地未提交修改，不强推。
- 不为最终性能测试引入 Kafka、Nacos、JMeter集群、Grafana、React/Vue/npm、新数据库表或服务进程；已有 Python 标准库就够。
- 不把 429/503 计为业务成功吞吐，不以 B2 的两组 15s 结果充当 Block4 正式三组对照，不伪造 P99/CPU 结果。
- 不让 Gateway 使用 Servlet/JDBC/同步Redis；关键Java/Python/JS按业务阶段写准确中文注释，长逻辑按1/2/3编号。
- 不修改/合并 main，不切换未授权功能阶段。**本次用户授权的是功能4实施与测试，不是批准最终把所有 PR 合并。**

## 4. 提交与收口格式

按实做提交，例如：
- `test(block4): verify cross-block regression and three-mode performance`
- `fix(blockN): repair ... found by final regression`
- `docs(block4): record actual acceptance evidence`

最后回答负责人与ChatGPT：**Git当前分支/完整SHA/推送成功与否；实际运行的测试命令和各项数字；三组真实样本与局限；异常/未执行/修复情况；前端和指南链接；人工验收仍需做哪些；PR与main现状**。

**Codex 的任务以本阶段最小可靠实施和真实测试为主，无须自行重新设计系统。遇到阻塞要报告具体文件、命令和错误，不能把未运行填为通过。**
