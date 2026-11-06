# FlowGate｜最终综合验收计划 V1.0（准备稿）

> 2026-11-06。状态：**负责人已授权功能块4执行，具体合同见 `docs/progress/block-4-acceptance.md`；已提交三组性能脚本和统计单测，但全量 Maven、真实三组 HTTP 性能、故障/长期容量与负责人真实浏览器体验尚未执行。** 发现问题需收口并留证，不得借“已提交”自动合并 main。
> 基线：`feat/block3-resilience-observability`；四个功能块 Block0–Block3、现有三 JVM（Demo/Control/Gateway）+ MySQL/Redis + 原生业务页面。
> 审核参照：[功能块 3 源码复审](../reviews/block-3-static-review-2026-11-06.md)、[现有使用指南](../guides/test-console-guide.md)、[故障演示](../guides/block3-fault-testing.md)。

## 1. 原则和运行边界

- 尽量使用原项目 Maven/JUnit、Python3 标准库、Node 内置能力、现有 Docker Compose（仅自建隔离库/端口/容器），**不安装 JMeter、Grafana、Kafka、Testcontainers、前端框架或额外中间件**。
- 用户持久化 `.env`、MySQL、Redis、Docker volumes 和本地 IDE 进程都是受保护资产；测试采用独立随机数据库、随机回环端口和仅测试自有的进程。禁止 `docker system prune`、删除用户数据卷、强制清理共享端口。
- 使用 **Java17**，记录 `git rev-parse HEAD`、`java -version`、Maven/OS/CPU/RAM、实际测试模式和启动命令。日志和原始数据需要脱敏；不要提交密码/Token、Authorization Header、内部 Key、`.env`。
- 初次只做功能回归，完成并处理缺陷后才做性能对照；出错时明确失败，不调整结论掩饰。性能实验单机结论不能被描述为生产吞吐/SLA。
- 代码开发和测试时优先只跑直接受影响模块；**此最终阶段**才允许覆盖所有模块的统一回归与正式对照实验。

## 2. 阶段门槛：最终测试开始之前

**新增P0**：负责人要求在正式验收之前完成独立产品管理后台 `/console/`，参见 `docs/design/product-console-v1.md`。原 `/test-console/` 仅是开发/测试工具，不作为产品UI；产品管理业务完整编码、定向测试及人工反馈收口前不得运行正式三组性能验收。

- [ ] `/console/` 正式产品后台已实现登录、工作台、服务/API/路由、治理策略、发布、调用方和运行监控；不要求用户手填JSON/内部ID；已按产品合同定向复测。
- [ ] PR #4 的 Block3 静态审核结论已阅读；负责人对 **新产品后台 `/console/`** 至少一次真实浏览器使用并记录反馈。尤其检查首次业务路径、列表/编辑/发布、角色权限、窄屏与状态。旧开发测试台仍需保留。
- [ ] 缓存取消恢复及迟到事件交错 JUnit 再复测通过，确保真实路由缓存/配置版本一致；旧发布快照 checksum 未被修改。
- [ ] 基线提交和测试环境固定，`git status --short`、`git log -1` 已记录；无关键代码在测试过程中无说明地变更。
- [ ] 完整启动与清理路径可复现，测试运行前后原有数据库/端口/容器未变化。

## 3. 功能回归（全部模块一次）

在项目根目录，配置 JDK17 后：

```bash
mvn -B -ntp clean verify
node --check flowgate-control/src/main/resources/static/test-console/app.js
node scripts/block2-console-test.js
node scripts/block3-console-test.js
python3 scripts/build-console-guide.py --check
```

`clean verify` 仅在最终统一阶段运行，记录实际测试数/失败数/跳过数和命令原文；避免用户本地运行中的 Jar 文件被清理影响，先使用隔离工作目录和已停的自有进程。

| 功能块 | 必须验证的业务行为 |
|---|---|
| Block0 | fast 200、echo 201、slow 0..2000ms、异常 400、Header/Query/编码路径、可信 X-Request-Id、Actuator 指标 |
| Block1 | ADMIN/OPERATOR/VIEWER 权限、Caller 401/403/200、Redis 到期/吊销、路由白名单、CAS 409、发布/回滚 checksum、节点应用确认 |
| Block2 | Lua 单 Key 并发/补充/TTL、GLOBAL→SERVICE→API→ROUTE→CALLER、跨 Gateway 共享速率、在途许可 complete/error/cancel/timeout 释放、429/503 拒绝不触达 Demo |
| Block3 | 200/500/503/504、GET fallback 503 degraded、POST 禁止、OPEN 无下游命中、HALF_OPEN 两次探测、节点状态与低基数指标、Control/Redis 恢复及旧 PENDING 兼容 |

真实 HTTP 环境按现有脚本回归：`scripts/block1-smoke.py` 的 MySQL socket 前置条件需符合其专属测试要求；`scripts/block2-smoke.py`、`scripts/block3-smoke.py` 采用各自隔离依赖，执行前先阅读脚本实际安全边界，避免重复占端口。必要时合并最少公共初始化，**不为减少重复引入复杂测试框架**。每个脚本的实际测试结果按工具状态分别记录，不把“存在文件”当“已运行”。

## 4. 负责人真实浏览器操作验收

**正式业务产品地址**：`http://127.0.0.1:8081/console/`；**技术实验页面**：`http://127.0.0.1:8081/test-console/`（离线 `guide.html` 继续使用）。请先确认新产品确实完成，再进行下列人工验收。不要用假数据或只看截图。

1. **首次进入**：在新产品后台登录，工作台看到真实健康/节点状态；不读Java源码完成创建服务/API/路由（或选择已有）、配置策略、保存草稿、发布并确认节点。fast 200/echo 201 等HTTP实测可从旧开发工具进行。
2. **服务/API/路由目录**：表单关联下拉、已有数据重读、只保存草稿不误宣称已生效、高级 JSON 折叠页，及可能的错误 400/409。
3. **身份与权限**：ADMIN/OPERATOR/VIEWER 角色按钮的禁用与真正 HTTP 403 一致；Caller Token 仅一次性展示/浏览器内存，刷新后清空。
4. **流控保护**：小批测试复现真实 429 RATE_LIMITED、503 CONCURRENCY_LIMITED、504 UPSTREAM_TIMEOUT、503 CIRCUIT_OPEN、503 DEGRADED（有 degraded=true）。
5. **恢复与版本**：节点 ONLINE/STALE、PERSISTED vs APPLIED、等待恢复与回滚之后的策略行为；不得用前端计时器冒充熔断状态。
6. **体验最低标准**：1280px 与窄屏单列下每个关键按钮可点、字不溢出、Tab/表单说明正确、帮助链接可在本地离线使用；用户指出问题在当前分支修复并复测。浏览器 Network/Storage 不留 Token/密码或不相关外部 API 请求。

负责人手工完成这些操作，并留下无敏感信息的简短结果表（通过/失败、页面/按钮、真实 HTTP 状态、截图如愿意）。**Codex 的 Node 模拟 DOM 测试不能代替真人体验。**

## 5. 最终性能三组对照（有资格后才执行）

同一宿主、同一次明确记录的环境和资源上限、同一 Demo 数据/路由/Caller 认证约束、同样客户端请求数量和发送节奏，分别做：

| 组别 | 请求路径 | 目的 |
|---|---|---|
| A：直连 | 测试客户端→Demo | 下游原始容量与延迟基线 |
| B：仅代理 | 客户端→Gateway→Demo，认证一致、无治理策略（或固定明确的最小必要认证） | 网关引入的额外开销 |
| C：启用治理 | 客户端→Gateway→Demo，绑定限速+本机并发与必要故障保护 | 在相同热点负载下普通接口保护与拒绝效果 |

**不要把 A 与 B/C 的不同认证成本、不同请求路径或不同负载视为公平对照**；若确实无法等价，分别报告限制。场景覆盖普通流量、含慢请求的热点竞争和可控上游故障；每组至少有足够成功样本支撑分位数（建议先短预热，再按固定请求量/时间窗口正式采样），样本不足则不宣称 P99 结论。

统一记录：总尝试、HTTP 2xx/4xx/429/503/504/5xx 数量、业务成功吞吐、平均/P50/P95/P99、下游实际完成量/在途峰值、CPU/内存、Gateway 开销与拒绝比例。**成功请求延迟分布与所有请求分布分开**，避免大量快速 429/503 导致“总体 P95 很低”却隐藏业务成功率下降。

性能阈值不事先编造“提升 XX%”，结果解释以实际稳定数据/误差/资源瓶颈为依据，重复实验并保留原始脱敏样本与生成命令；如无改善，应如实说明、定位是隔离、拒绝、负载还是观测模型原因。

## 6. 故障、稳定性、容量边界

- Control 断联后旧路由继续受原权限/限流/熔断保护；恢复后周期补拉追平，不虚报已应用。
- Redis 暂停/恢复，Caller 与受限路由 fail-closed；无绑定限流的公开路由符合原契约。
- 两 Gateway，某节点掉线后另一个节点可执行最近已应用配置，旧节点 freshness 转为 STALE；恢复后再上报。
- 小规模多次发布不同 CB 参数 revision，观察官方注册表实例数、指标基数、堆内存增长；明确是否存在无限增长风险，必要时提出最小约束或清理策略。**本阶段无需生产级持续数周稳定性承诺**。
- 客户端取消、Gateway 超时、断路器 OPEN、Control/Redis 异常发生时本机许可无负数、不泄漏；区分“请求已终止”和“Demo Servlet 工作线程仍在运行”。
- 测试排查时不记录密码、Token 或内部密钥；Actuator `gateway` 写管理端点不得开启。

## 7. 最终交付和 Git 合并检查

统一汇总 `docs/acceptance/final-results.md`（**真正执行测试后按结果创建，不得预填通过**）：构建命令/总数、浏览器验收记录、三组负载原始数据路径、故障实验、异常处理、风险剩余、Commit SHA/依赖清单。

分支现为 stacked PR：Block1→main，Block2 设计→Block1，Block2 实现→设计，Block3→Block2 实现。负责人审核全部通过后**按依赖顺序**处理，必要时审慎重设后续 PR base；合并时保留原始 Conventional Commits 与文档证据，不强推、不直接 squash 造成版本/证据难以对应。合并前检查 GitHub PR diff、CI（若可用）、main 实际 SHA，合并后再从 main 再次完整验证并打可追溯 tag。

最终成功标准：功能行为与安全失败模式通过，前端可独立按指南操作，三组性能实测结论可信且可复现，遗留限制清楚，主线合并及源码/许可证归属检查完成。**本计划创建时所有这些门槛都仍未最终验收。**
