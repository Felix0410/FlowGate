# FlowGate V1.1-E：本地完整交付收尾（可执行 Codex 任务书）

> **任务状态：待 Codex 编码与验证；本文件是任务分配，不代表功能已完成。**
> 基线：`feat/v11-console-workbench` 的 `dc7c0c479439ce7f2d106ff60ecffda57aab2e66`。
> 本文为负责人 2026-11-09 手动通过 V1 主要治理和 V11-A 后新增的**产品交付收口任务**。按 E0→E6 实施，最终交 ChatGPT 代码复审和负责人真人验收。
> 执行入口：仓库根目录 `CODEX_V11_E_LOCAL_DELIVERY_PROMPT.md`；本任务与尚未开始的 `docs/progress/v11-d-acceptance.md` 相互独立，**先解决本地完整可用性，再考虑 D 的 10 万行 SQL、四组压测和大故障矩阵**。

## E-00 背景、边界与目标

### 已实现、不重复开发

- V1：服务/API/路由、发布/节点应用、旧 Caller、Redis 限速、单 Gateway 并发、超时、熔断、审计，负责人已按测试路径完成主要人工验证。
- V11-A：客户、应用、套餐、订阅的保存、关联详情，负责人已完成主要人工验证。
- V11-B：独立 `fgapp_` 应用凭证、MySQL 余额/Ledger/Outbox、私有 API 的显式月度 Quota 与可信节点能力门禁，已有代码和隔离验证；**负责人当前常驻环境尚未确认激活**。
- V11-C：Control-only Kafka Outbox 投递、幂等聚合、用量与对账、失败重投/原事件补投，已有代码和隔离验证；**负责人当前 Kafka/异步开关与真实读模型状态未确认**。
- V11 工作台：七大业务域和20项旧能力、上下文导航、产品手册已经实现且在 `feat/v11-console-workbench`；真实浏览器隔离测试通过，负责人尚未完成 B/C 人工链路。

### 当前缺口（依据源码）

1. `scripts/run-local.py start` 启动 MySQL/Redis + Demo/Control/Gateway，不提供 B/C 的分阶段安装、Kafka 就绪核对、完整演示模式或业务链路诊断；默认 `/console/` 已实现。
2. `.env.example` 中 `FLOWGATE_QUOTA_ACTIVATION_ENABLED=false`、`FLOWGATE_QUOTA_TRAFFIC_NODES=''`、`FLOWGATE_ASYNC_ENABLED=false` 是安全默认值，不能直接全局改成 true。
3. `compose.local.yml` 的 Kafka 使用 `mq` profile、持久 `kafka-data` 卷；不能假设每个人原有卷已迁移，也不能用 `down -v` 重新开始。
4. `sql/flowgate-init.sql` 只负责旧基线，V11-A/B/C 增量分别在 `sql/flowgate-v11.sql`、`sql/flowgate-v11-c.sql`；已有数据库重启不会自动安装新表。
5. `/admin/quota/status`、`/admin/nodes`、V11-C 异步事件及 Kafka 状态已有真实接口，当前缺少面向用户的**统一、准确、可操作**的就绪汇总。
6. D 阶段还承担性能、SQL、跨节点失效及全局回归，不能把那些庞大工作混到本轮“一键可用性”中。

### 必须达到的用户体验

- 常规模式保持原行为；使用者可按**一份简明中文指南、一个启动入口和一次预检**在**明确隔离的本地演示环境**完成 B/C 链路，不必自己查找隐藏的环境变量。
- 工作台能够回答“为什么不能签发 APP Token / 发布计量规则 / 查到异步用量”，并指向明确的下一步；不能只有“功能未就绪”四个字，也不能到处塞口号。
- **独立演示成功**与**用户已有环境完成部署**是两件事，脚本和文档必须分别标识。功能开关只能在校验完毕的演示环境中自动打开，生产/现有环境默认保持 fail-closed。

## E-01 不可突破的硬约束

1. **不触碰已有数据与进程**：默认只读检查；不得修改或覆盖根 `.env`、用户自建 MySQL/Redis/Kafka 库卷、历史 Ledger/Balance/Outbox、IDE run configurations；不得 `docker compose down -v`、`docker volume rm`、`DROP`、`TRUNCATE`、`reset --hard`、`git clean -fd` 或杀端口占用进程。
2. **新增隔离演示环境**必须使用与现有 `flowgate-local` 不同的 Compose project、容器、数据库和命名卷；只接管工具自身新建且能核对身份的进程。默认停止只停工具自己启动的 JVM/容器且**保留**数据卷，任何删除演示资源都需负责人单独明确授权。
3. **不降低安全门禁**：原来的节点完整名单、心跳15秒、版本/checksum、`USAGE_QUOTA_V1` 能力、配额安全拒绝、ADMIN 权限、Scope、计量私有 API、MySQL 余额权威及Kafka幂等逻辑不得削弱；禁止通过硬编码“ready=true”绕开。
4. **密钥与Token**：不在命令行参数、URL、日志、截图、JSON证据、Git、localStorage/sessionStorage泄漏。可沿用本地未跟踪 `.env` 中既有配置，但不写回；必要的管理员原始密码只允许交互式输入、只驻留进程内存。所有生成的本地运行/诊断文件位于 `.local/`，目录权限0700，涉及密钥文件0600，输出脱敏。
5. 维持 Java17、Spring Boot4、MySQL8.4、Redis7、Kafka4.1.1、Python3标准库、原生 HTML/CSS/ES Modules；不加 React/Vue/npm、Kafka Streams、CDC、K8s、配置中心、新服务或新的数据库表。较长新代码用中文注释并按 1/2/3 标步骤。
6. **不把 D 算进 E**：不执行全局10万条SQL、正式四组性能对照、全故障矩阵、PR合并、打正式Release。E交付后单独申请 D 授权。
7. 若现有用户端口被占用，必须报出占用/冲突并停止；绝不杀掉对方进程，绝不绕过端口检查而连到意外服务。修改端口时必须全链路一致（Control URI、Console Origin、Gateway指标链接、Demo白名单），否则禁止报就绪。

## E-0：锁定源码与安全盘点（第一提交）

**输入**：`AGENTS.md`、`README.md`、`scripts/run-local.py`、`compose.local.yml`、`.env.example`、B/C的 controller/service、SQL、`/console/`、已有指南和 smoke。

**执行**：
1. `git status -sb`、`git log -1`、`git branch -vv`；已被用户修改的文件先列出，不覆盖、不重置。只在本次 E 工作分支编码（建议 `feat/v11-e-local-delivery`，基于包含本合同的 docs 分支）。
2. 汇总服务依赖/真实就绪条件，特别检查启动时的 `V0→非计量V1发布→节点上报能力→签发→计量V2发布` 顺序。确认各应用配置项读取来源（Control/Gateway/Demo），Kafka listener 仅在 Control。
3. 检查 B/C schema 文件执行重复性、现有 preflight SQL 的四个历史风险、单节点/多节点资格，确定独立测试库的升级顺序。
4. 输出 `docs/progress/v11-e-local-delivery-implementation.md` 的“当前事实”章节，不预填通过结果。

**完成标志**：资产不被修改；列出真实接口、风险、可重复命令和最终脚本参数；`git diff --check`。

## E-1：一条只读的环境诊断命令

**新增可执行入口**：`python3 scripts/v11-local.py doctor`；另支持 `--json` 输出脱敏结构化状态。

1. 检查 JDK/Jar 和源码是否是本轮新工作台、Docker/Compose可用、应用端口和 PID 所有权、已有 MySQL/Redis 的连通性、所需 V11 B/C 表是否存在、**只读**执行 `sql/v11-b-upgrade-preflight.sql` 四个风险计数。
2. 运行态检查真实 Control、Gateway、Demo 状态；可在登录后的业务工作台通过既有管理 API 继续读取发布版本、Gateway在线/应用版本/能力、B激活开关与完整流量节点名单、C开关/Broker和异步状态。不要用未认证接口越权读取管理员信息，也不要冒充能从 CLI 读取到页面凭证。
3. 每项展示 `READY` / `BLOCKED` / `UNKNOWN`（请求失败或权限不足）三态及人类可操作的修复建议，例如“尚未迁移C两表”“当前网关未应用V1”“缺可信计量能力”“Kafka未就绪”“B关闭为安全默认”。
4. 诊断不可对数据库写入，不能启动/重启进程、自动迁移或切换功能开关；不使用简单端口开放代替Kafka协议就绪。命令返回码明确记录：0=所选配置就绪，2=发现阻塞，1=工具自身失败；`--json` 格式稳定，隐藏 URL 中的密码。
5. 核心诊断逻辑应可使用测试夹具验证所有三态/缺项，不强制用户具备 Kafka 才能查看 V1 基础状态。

**验收**：在“只启动V1”“B表缺失”“无可信节点”“Kafka未起”“全部具备”场景中诊断均不误报 READY；`doctor` 前后数据库和环境文件不改变。

## E-2：安全的完整 V1.1 演示启动路径

**目标命令合同**（如不符合既有运行器，可新增最薄兼容包装，但用户指南与测试必须统一）：

```bash
# 不改变用户已有数据库/端口/进程，仅诊断
python3 scripts/v11-local.py doctor

# 明确创建/启动本项目专用隔离 V11 演示环境：新项目、新库、新卷
python3 scripts/v11-local.py up --isolated

# 查看当前演示环境状态（不显示秘密）
python3 scripts/v11-local.py status --isolated

# 只停止该脚本管理的演示 JVM/容器，默认保留所有命名卷
python3 scripts/v11-local.py stop --isolated
```

1. 新隔离 Compose project 不能叫 `flowgate-local`，不能共享原来的 `mysql-data`、`redis-data`、`kafka-data` 命名卷；为与原环境共存配置独立 MySQL/Redis/Kafka 端口。Control/Gateway/Demo 默认仍使用8081/8080/8085，遇到占用应清楚提示“请先手工停止已有本项目服务或更换受支持端口”，不能自行终止。允许做一致性端口覆盖时须同步修复前端原有硬编码的Gateway指标地址，不能无声破坏已通过的业务链。
2. 按“依赖准备 → **仅对新库**初始化 V1 和迁移 V11-A/B/C → 四项只读风险预检 → Kafka协议就绪 → 启动Java17三服务”的严格顺序执行；数据卷已存在不能误识别为新库而跑覆盖性初始化。新环境 schema 也须幂等、重复启动不破坏原有授权记录。
3. 从现有本地 `.env` 只读取得管理员 BCrypt、基础秘密与上游白名单；其他演示配置在进程环境中覆盖，输出到 `.local/v11-demo/` 脱敏信息，不回写根 `.env`。如果缺必填值，安全退出并提供修改指南；绝不能生成或打印默认管理员明文密码。
4. **完整演示模式**允许在满足全部条件的**新独立环境**内显式设置 `FLOWGATE_QUOTA_ACTIVATION_ENABLED=true`、`FLOWGATE_QUOTA_TRAFFIC_NODES=<真实完整名单>` 和 Control-only `FLOWGATE_ASYNC_ENABLED=true`；脚本必须核对实际 `FLOWGATE_NODE_ID` 与名单一致。不可通过全局修改 `.env.example` 或 `.env` 来实现“默认启用”。
5. 零版本时不能先签发：明确引导用户在工作台先创建并发布一条**非计量 Demo API**，等 Gateway heartbeat 上报 `USAGE_QUOTA_V1` 可信能力且版本一致后再签发和发布计量规则；可以在隔离模式里提供明确授权后的最小初始化向导，但不能伪造版本或偷偷写入用户原数据库。
6. Kafka 不在或不可用时不能悄悄宣称“完整 V11 就绪”；必须明确显示异步功能 BLOCKED 和原因，旧 V1 非计量链可独立可用。检查 Control 日志/状态确认 Topic 与 listener 真实启动，不以“Kafka TCP端口通”代替消费者就绪。
7. 整个脚本需要严格进程归属校验、失败回滚**仅退出自己刚启动的进程**（保留卷与账本）、重入幂等、重启后可恢复，不需要让用户手动执行10条隐藏的 Docker 操作；单机 Mac/Linux 与现有 Python3/Java17/Docker Compose 契约兼容。

**验收**：原 `python3 scripts/run-local.py start` 不回归；演示模式可完整启动后正常访问 `/console/` 并看出真实未就绪/已就绪；二次 up/stop 不破坏卷，旧环境持续健康。

## E-3：工作台“真实就绪状态”与无歧义指引

1. 在现有七业务域工作台、`发布中心` 的合适位置复用小型状态/异常区，不增加第八个一级菜单、欢迎大标语或全天候教程。
2. 只用既有管理 API 的真实数据汇总以下状态：V1路由发布（当前持久版本+节点应用）、V11-B（月度计量开关+完整名单+可信节点能力）、V11-C（异步开关+Broker/Worker状态）；数据库迁移如果不可从后台直接验证，应标为“未验证”并链接 `doctor` 指令，不新增一个未经认证的敏感探针。
3. 提供一句话原因+下一动作的中文提示，如“未发布非计量初版，请先在发布中心发布并等待节点上报”“计量未启用，请检查本地交付手册”“异步关闭：账本可用但日报暂不聚合”。
4. 每个单项状态 `READY/BLOCKED/UNKNOWN`，未知不当作0、已关闭不当作代码缺失、SENT不当作已统计、`PERSISTED`不当作已应用；无权限者不显示可执行写操作。
5. “签发APP凭证”按钮不可用时解释真实门禁，不引导用户绕过；保留一次性Token、离页清除和敏感参数白名单。
6. 中文帮助更新与原生 `/console/guide.html` 同步；重新运行 `python3 scripts/build-console-guide.py --product` 并做 `--check`；375px/1280px、键盘、浏览器前进后退不退化。

**验收**：无误导性绿灯、无状态口号、错误和操作建议可见、旧20项能力不丢失。

## E-4：可重复的 B/C 实际业务闭环（隔离环境）

只在 E-2 的**新隔离库**或受脚本明确证明属于测试夹具的库运行写操作，使用不与用户历史数据冲突的测试标识。自动验证必须有真实服务/真实MySQL/Redis/Kafka请求，不能用 DOM 假响应冒充业务通过。

1. ADMIN 登录，检查无 Token 访问私有接口返回401、Scope错误返回403。
2. 真实创建客户→应用→“每月5单位”套餐→当月唯一订阅；关联详情与前端跳转符合事实。此处若测试环境中已存在同月订阅，优先改用新 app/唯一标识，不删除或覆盖用户账本。
3. 先发布公开非计量API，等待所有可信接流节点应用该版本并上报能力；再签发独立 `fgapp_` Token；建立 GET 私有API和唯一生效 Quota（`unitsPerCall=1`），发布并确认。
4. 同一APP同一月发6个真实 HTTP 请求，前5个应通过并各写一条权威 Ledger+Outbox，第6个429 `QUOTA_EXHAUSTED`；MySQL余额为总5、剩余0，拒绝不新增Grant；旧Caller不能冒充APP，旧非计量接口继续正常。
5. Kafka消费到日报后检查授权单位和次数准确为5、事件幂等；不能用用量读模型驱动余额扣减。Broker临时不可用的**隔离测试**须见Outbox积压、恢复后追平，但不能反复重放新业务请求制造重复扣量。
6. **时间口径**：今天的UTC日通常未封账，生产“对账中心”只能查询昨天及更早；隔离测试需要有真实、受控的UTC过往事件/时钟注入方案才能断言MATCH，绝不能把今天未封账数据假设为已对账。页面应能明确说明“今天未封账”，不可造假结果。
7. 验证受控的409订阅冲突、403只读角色、Token一次性可见/轮换或撤销后的旧代次拒绝。禁止把签发后的Token写入测试输出、JUnit日志、截图或证据。
8. 逐项说明哪些是“后端自动测试通过”、哪些“负责人当前已有环境亲自通过”、哪些“未验证”；不能拿隔离脚本运行结果直接宣布用户的常驻环境已激活。

## E-5：回归测试与可复现交付记录

### 必须实际运行并记录退出码

- Java17：至少 `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`；如果该轮涉及更多模块，补相应范围。
- Node：已有产品/工作台/B/C合同，新增 readiness/UI 检查，旧 HTML/CSS/ES modules功能不丢；两种视口至少各一轮真实Chromium可见性/键盘实测。
- CLI doctor：不同失败/就绪状态下的只读测试，以及 `--json` schema；包含“状态 UNKNOWN 不是 READY”“错误端口不接管”“二次启动幂等”“停机保留卷”的安全用例。
- 新隔离真实HTTP：B 5/6+Ledger/Outbox、Kafka真实消费/日报，复用原 `scripts/v11-b-smoke.py`、`scripts/v11-c-smoke.py` 不改变旧预期；有关E新流程必须另外出具证据。
- `python3 scripts/build-console-guide.py --product --check`、`git diff --check`。

### 交付物

1. `scripts/v11-local.py`（和必要的最小辅助文件；独立运行即可诊断与演示启动），必要的隔离 Compose 配置；
2. 工作台真实就绪指引与旧20项能力回归；
3. `docs/guides/v11-local-delivery.md`：新手从环境检查→隔离启动→非计量版本→签发→计量→Kafka用量→停止，包含常见错误及退出模式；README 加一条正确入口；
4. `docs/progress/v11-e-local-delivery-implementation.md`：每段任务提交SHA、执行命令、实际退出码/失败修复、环境与数据隔离证据、尚未执行条目、已知限制；
5. `docs/progress/evidence/v11-e-local-delivery.json`（**只有真实执行后才生成**，不记录任何 Token/密码、敏感服务环境和私有响应；截图只含脱敏UI）。

### E-6：人工验收门槛（必须由负责人完成，不得代签）

- [ ] 原已有本地 V1 配置和客户/订阅/账本、用户 `.env`、Redis/Kafka命名卷未被修改或删除。
- [ ] 不读源码可理解 `doctor` 诊断与具体修复建议，能区分 V1、B、C 各自就绪。
- [ ] 能从独立演示启动入口进入 /console/；没有隐藏“去源码里配开关”的步骤。
- [ ] 先发布非计量版本并确认 Gateway applied，再签发 APP Token/配置计量私有API。
- [ ] 真实5次准入+第6次额度耗尽，余额与5条Ledger/Outbox一致，旧非计量接口继续可调用。
- [ ] Kafka 投递、日报聚合有真实结果，今天未封账/历史MATCH的适用条件讲清楚。
- [ ] 异常时明确知道原因和下一步；关闭演示不会删除数据、不会影响既有服务。
- [ ] 1280px与375px、键盘、刷新/跳转、角色权限及敏感Token生命周期真实可操作。

任何一条失败：状态标为“待整改”；E阶段不得宣称正式交付通过。

## 提交规范与停止点

- Codex 从包含本任务书的 `docs/v11-e-local-delivery-closeout` 分支创建独立 `feat/v11-e-local-delivery`，不接在 main、不修改旧 Draft PR。
- **E0、E1、E2、E3、E4、E5/E6文档各至少一次提交**，每段更新进度；提交风格 `docs(v11-e): ...`、`feat(v11-e): ...`、`test(v11-e): ...`、`fix(v11-e): ...`。
- 不得改写旧阶段历史或“修好后自动合并”。可创建新 Draft PR 指向本任务分支；所有旧 PR 保持 Draft，未经负责人明确授权不合并。
- 完成代码与定向验证后**停止**，汇报对应提交SHA、PR、已运行证据、未完成项，交 ChatGPT 复审和负责人 E6 人工验收。**D 阶段另行决定。**

## 空进度表（Codex 真实完成后填）

| 阶段 | 状态 | Commit SHA | 实际命令/结果 | 剩余风险 |
| --- | --- | --- | --- | --- |
| E0 基线审计 | 未执行 | — | — | — |
| E1 doctor | 未执行 | — | — | — |
| E2 隔离演示启动 | 未执行 | — | — | — |
| E3 前端就绪提示 | 未执行 | — | — | — |
| E4 B/C 真联调 | 未执行 | — | — | — |
| E5 回归与文档 | 未执行 | — | — | — |
| E6 负责人真人验收 | 未执行 | — | — | — |
