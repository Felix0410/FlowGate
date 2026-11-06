# FlowGate｜本地 Codex 协作入口

> **最新状态（2026-11-06）**：功能块 0–3 的主要业务代码已交付，功能块 3 源码静态复审已完成；PR #1–#4 仍为 Draft，`main` 尚未合并这些功能块。**现在停在审核/人工体验及最终综合验收准备阶段，不要自行开始新的功能块或长时间压测。**

## 当前工作基线

- 实现分支：`feat/block3-resilience-observability`，已包含 Block1/2/3 的业务代码；相应 PR 为 [#4](https://github.com/Felix0410/FlowGate/pull/4)（叠加于功能块 2 的实现分支）。
- Block3 实际实现与 Codex 定向测试见 [阶段进度第11节](docs/progress/block-3-resilience.md)，含源码、测试命令与 192 条隔离 HTTP 证据。**报告不是最终验收**；浏览器人工操作由负责人执行。
- 已完成源码静态核查见 [Block3 审核报告](docs/reviews/block-3-static-review-2026-11-06.md)；下一阶段应先阅读 [最终综合验收计划](docs/acceptance/final-acceptance-plan.md)。
- 业务页面使用指南：[完整中文说明](docs/guides/test-console-guide.md)、[故障演示](docs/guides/block3-fault-testing.md)；页面内另有同源 `/test-console/guide.html`。页面操作体验仍需负责人实际浏览器体验。

## 等待负责人下一步指令

1. 如果负责人反馈网页难用/按钮异常：**仅修明确问题**，保留 Block0/1/2/3 功能；更新前端代码及中文指南，同一功能分支提交，执行变更范围定向测试。
2. 如果负责人明确授权“开始最终综合验收”：以 `docs/acceptance/final-acceptance-plan.md` 为契约，先确认测试隔离环境和已存在用户数据，执行全工程功能回归、两 Gateway/故障场景、三组对照性能，逐步记录实际结果。**在得到明确授权之前，不要运行最后的完整压测。**
3. 各 PR 仅在负责人同意且证据完备后按依赖顺序审核/合并。禁止强推、`reset --hard`、`clean -fd`、直接改 main、覆盖 `.env`、删除非本项目 Docker/数据库资源。

## 永久编码/测试约束

- Java17 + Spring Boot4 / Gateway WebFlux、MVC Control、MyBatis-Plus、Reactive Redis、Micrometer，熔断只使用官方 Gateway CircuitBreaker/Resilience4j，不为修复小问题引入新中间件/前端框架。
- 网关热路径无 JDBC/Servlet/同步 Redis/阻塞调用；已发布快照 PENDING/ENFORCED 状态安全明确；认证与流控不可被降级绕过。
- 中文注释清晰准确，长段业务代码按 `// 1.` `// 2.` `// 3.` 分点；源码和 `/test-console/` 与指南每次同步。
- 变更要有真实测试命令和结果、Conventional Commits、可追溯 SHA，不提交密码/Token/内部 Key/日志或构建产物。测试过的才写“已通过”，模拟 DOM 不能写成真实浏览器验收。

**当前结论：功能块 3 代码已远端提交并完成静态审核，等待真人体验和最终统一验收。**
