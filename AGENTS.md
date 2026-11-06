# FlowGate｜当前本地 Codex 唯一入口

> **2026-11-06 最新负责人要求：V1 正式交付之前，把目前仅开发者能看懂的测试台，改造成真正可用的产品管理后台。**
> 因此 **当前首要工作是PC-00..PC-06产品后台开发**，不是先跑正式三组性能实验，也不是继续堆调试按钮。Block0–3 业务代码已有；Block4 综合验收已授权但须在产品页交付后执行。
> 本文是统一仓库入口，技术/交互的唯一权威详细合同为 `docs/design/product-console-v1.md`，实施提示为 `CODEX_PRODUCT_CONSOLE_PROMPT.md`。

> **当前实施状态**：PC-00..06编码及定向验证已经完成，主实现`0758787`，实际记录见产品合同第8节与Block4进度5.3。下一步处理ChatGPT复审/负责人真实浏览器反馈；不要重新开发已有后台或启动正式长对照。原df00937基线测试已保留，与新产品验收分开记录。

## 1. 先做的产品改造

- 远端分支：`test/block4-final-acceptance`（保留 Block0–3 实现和 Block4 现有脚本）。`git status -sb`、`git fetch origin`，核对差异后安全检出；**不要从main重新创建，不覆盖用户修改**。
- **优先阅读**：
  1. `docs/design/product-console-v1.md`（真实产品后台的导航、业务流程、CRUD、状态、安全、UI、测试与完成标准）。
  2. `CODEX_PRODUCT_CONSOLE_PROMPT.md`（PC-00..06本地可执行任务）。
  3. `docs/progress/block-4-acceptance.md` 第0节（先产品化、后正式验收）。
  4. `docs/standards/frontend-sync-rule.md`、`docs/guides/test-console-guide.md` 与实际后端Controller/实体模型。
- 新产品主入口 `/console/`：独立登录页、左侧导航与工作台、服务/API/路由列表与完整表单、四类规则、发布回滚、Caller、节点/指标/审计，真实版本/权限/空状态/报错。
- 旧 `/test-console/` 原样保留所有功能，明确定位为开发/验收工具；不把HTTP调试堆在新产品首页。若只读绑定查询确实缺少，可按合同最小扩展现有Control GET API并定向测试，无新数据库/新后端服务。
- 没有产品前端完整可操作、定向测试、使用指南和负责人浏览器反馈前，**不启动Block4正式三组长对照**，不把“用户体验已通过”写进进度。
- 正常代码提交到该分支：`feat(console): ...`、`test(console): ...`、`docs(console): ...`，更新阶段文件与README，交由ChatGPT/负责人审核。

## 2. 完成产品化后才继续Block4

1. 读取 `CODEX_BLOCK4_PROMPT.md`、`docs/progress/block-4-acceptance.md`（B4-00..06），隔离环境运行 Maven 全工程回归、旧HTTP实测与真正DIRECT/PROXY/GOVERNED三组性能对照；
2. 保留 `scripts/block4-performance.py` 和统计单测的真实运行数据，不能把HTTP429/503作为成功吞吐或用旧Block2短实验替代正式三组；
3. 真人浏览器 1280px/375px、键盘、权限/草稿/发布/回滚由负责人操作，Codex只做自动/真实HTTP测试并记录人工“待执行”，收到反馈后修复；
4. 最终真实结果 `docs/acceptance/final-results.md`，PR仍Draft，不合并main或标记V1完成，必须由负责人明确审批。

## 3. 永久约束

只复用 Java17、Spring Boot4、Gateway WebFlux、Control MVC/MyBatis、Redis/Lua、Micrometer/Resilience4j。前端继续原生 HTML/CSS/JS ES modules，**不引入Vue/React/npm/Vite/复杂UI框架**；设计质量来自信息架构和交互正确性，不是框架数量。Token只存在JS内存；不把控制面 `/internal/**` 或内部密钥暴露网页，不伪造任何在线/已应用/策略已生效。

重要 Java/JS 业务逻辑有准确中文注释，长流程 `// 1.`、`// 2.`、`// 3.`；只执行变更范围自动化测试，完整大回归留到产品化完成后。保护用户IDE进程、个人Docker/数据库卷、`.env` 与本地未提交修改；禁止 `reset --hard`、`clean -fd`、`docker prune`、强推和自行合并main。

**现在开始执行：`CODEX_PRODUCT_CONSOLE_PROMPT.md`，先完成真正的业务产品界面。**
