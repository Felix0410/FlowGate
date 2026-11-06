# FlowGate｜本地 Codex 唯一当前执行入口

> **2026-11-06：功能块4本机自动化执行完成，待复审与负责人浏览器验收**。Java17全工程34项JUnit、隔离HTTP/故障/容量、三组×两轮正式性能已实跑，见[真实报告](docs/acceptance/final-results.md)和块4进度末尾。**负责人浏览器体验、正式审核与main合并尚未完成，不能写成V1最终验收通过**。后续只处理本阶段复审反馈，不擅自重跑全部实验或合并。

## 1. 唯一当前任务

- 远端分支已建：`test/block4-final-acceptance`，从 `feat/block3-resilience-observability` 的已提交实现创建，当前 PR #1–#4 全是 Draft。先核对本地/远端 SHA 与未提交改动，再安全检出远端分支，不从旧 main 新建。
- 唯一详细任务契约：[Block4 最终综合验收](docs/progress/block-4-acceptance.md)。
- 唯一交接提示：[CODEX_BLOCK4_PROMPT.md](CODEX_BLOCK4_PROMPT.md)。
- 原有验收大纲：[最终验收计划](docs/acceptance/final-acceptance-plan.md)、[旧阶段摘要](docs/progress/final-acceptance.md)。
- 操作说明：[业务前端指南](docs/guides/test-console-guide.md) 与 [故障演示指南](docs/guides/block3-fault-testing.md)；业务页 `/test-console/` 已有 `acceptance.html` 验收说明入口，不运行压测、不存Token。

## 2. 现在按顺序完成

1. 保护本地 `.env`、未提交源码、IDE JVM、用户Docker数据库/卷，固定Java17与Git SHA；禁止 `reset --hard`、`clean -fd`、`docker prune`、强推。
2. 全工程 Maven `verify`，Node 模拟DOM、离线HTML指南检查、Python 统计单测；保留真实成功/失败/未执行数字。
3. 使用现有隔离Lab复测 Block1/2/3 业务 HTTP/身份/版本/限流/熔断/故障；不能让老脚本固定端口连接用户持久化MySQL。
4. 执行已提交的 `scripts/block4-performance.py`：先短冒烟，再**真实DIRECT/PROXY/GOVERNED三组 × 两轮**，区分成功RPS、拒绝和客户端丢弃；原始结果写本地 `.local/acceptance/`，脱敏后才允许摘要入库。
5. 有界检查CB不同revision累积、缓存刷新、断联恢复和并发许可；如发现缺陷仅最小修对应Block代码，定向复测再补必要整体回归。
6. 前端维持已实现全部业务按钮、真实状态和14章离线指南；新验收说明只负责用户看步骤/模式，不用JS发起压测。真人浏览器/窄屏/角色体验由负责人完成，Codex只记录待执行与反馈。
7. `docs/acceptance/final-results.md` 填写真正运行的命令/退出码/指标和未执行项，同步本阶段进度、README、Git提交SHA。功能分支 Conventional Commits，先交复审，**不得自行把任何PR合并main或宣布V1验收完成**。

## 3. 长期代码与验证原则

Java17+Boot4+Gateway WebFlux/Control MVC/MyBatis+Redis/Lua+Micrometer+Resilience4j；不引入Kafka/Nacos/Sentinel/新前端工程；Gateway热路径不阻塞。重要代码中文注释，长流程按 `// 1.`、`// 2.`、`// 3.` 分点。敏感信息/测试日志不提交；测试结果必须以真实运行和证据为准。

**启动：直接阅读 `CODEX_BLOCK4_PROMPT.md` 并按 `docs/progress/block-4-acceptance.md` 执行。**
