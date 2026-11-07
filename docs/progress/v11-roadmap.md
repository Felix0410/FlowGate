# FlowGate V1.1 开发路线与真实状态

更新：2026-11-07。**V11-A管理域编码和Codex定向验证已完成，ChatGPT源码复审发现两项P0边界待修复/重测；B/C/D仍未实现/未运行。** V1负责人最终验收尚未收口，不宣称V1.1全版本完成。

## 为什么要做这次业务调整？

旧 FlowGate 能完成统一路由、Caller 认证、动态策略、五维限流、并发保护、熔断恢复及性能实验，但作为“通用网关”的产品独特性弱。V1.1 不重造代理内核，而是在现有架构上增加**第三方开发者 API 套餐与累计准入配额**业务，使调用凭证、额度、用量、失败恢复、监控和数据一致性有真实的业务关系。

## 固定交付顺序

| 序号 | 文档 | 目标 | 当前 |
|---|---|---|---|
| 前置 | docs/progress/block-4-acceptance.md | V1 后台手测、完整回归、PR 审核 | 待负责人收口 |
| A | docs/progress/v11-a-business.md | 客户、应用、套餐、订阅、凭证入口占位、产品 UI | 管理代码与17JUnit/155HTTP已交付；同月换套餐/UTC会话两项P0待修，凭证未激活 |
| B | docs/progress/v11-b-quota.md | 可信计量准入、正式应用凭证/隔离、MySQL 原子扣额、幂等 Ledger 与 Outbox | 未开始 |
| C | docs/progress/v11-c-async.md | Kafka、可靠投递、幂等消费、用量查询与对账 | 未开始 |
| D | docs/progress/v11-d-acceptance.md | SQL 大数据实验、四组性能、双节点故障、产品验收与发布 | 未开始 |

- 业务与技术总设计：docs/design/openapi-platform-v1.1.md。
- Codex当前唯一修复指令：`CODEX_V11_A_REVIEW_FIX_PROMPT.md`，审核报告`docs/reviews/v11-a-static-review-2026-11-07.md`。A已编码，不重新开始A或提前开发B。
- 原有 /console/ 作为正式后台继续迭代、/test-console/ 只作为技术验证入口。每阶段同步 UI、指南与进度。
- 每阶段的真实执行日志和 Git SHA 以后按节追加，不提前勾选。完成 D 之后才申请审核是否合并，新功能绝不自动合并 main。

## 简历与演示的诚实边界

旧 V1 的测试结论来自 docs/acceptance/final-results.md；V1.1 规划不能写成“已实现 Kafka 账本、千万级 SQL 优化”。本设计有两项不可随意放大的限制：
- 月度额度授予要经 Control/MySQL 权威事务，带来额外 HTTP/DB 延迟与故障依赖；这是以真实性和可恢复性换性能的取舍。
- 配额是“请求准入授权单位”，不是“成功响应计费”或商业金额；后端失败后不自动退还，异步 Kafka 用量不能拿来决定扣量。


## 2026-11-06 设计复审与开发分配更新

- 审核报告：`docs/reviews/v11-design-review-2026-11-06.md`，判定为**有条件通过：业务定位和MySQL权威账本可行，R01～R06安全/一致性P0约束必须纳入实现**。
- 设计已在`docs/design/openapi-platform-v1.1.md`第12节锁定：APP专用Redis命名空间/Session用途、旧Gateway混合版本保护、ADMIN-only新管理业务、同chargeId跨UTC月份只授权一次、不可变套餐额度/排队订阅、队列式SKIP LOCKED限制。
- **现有V1情况**：`/console/`产品源码和定向联调已交付，但负责人浏览器体验/完整最新版统一回归/主线审核仍待收口。A可在**独立功能分支**做安全、不会被旧Gateway启用的客户/应用/套餐/订阅管理代码；不签发APP Token、不改Gateway热路径。**B启用业务数据面前必须先完成V1基线验收及A审查**。
- 开发只派**A0→A6**，见`docs/progress/v11-a-business.md`第A-06节。A分别提交 schema→后端管理/RBAC→订阅行锁→产品页→定向测试/HTTP→真实进度；B/C/D虽已分配后续交付范围但均不得并行开始编码。
- Git顺序：设计PR #6为Draft，开发`feat/v11-a-business`应以`docs/v1.1-openapi-platform`**最新SHA**为base，单独Draft PR，禁止直接修改main或把未经测试的V11当已完成。后续B/C/D再建独立阶段PR。
- **验收门槛**：每阶段均有真实Java17/Node/隔离MySQL-Redis(/Kafka)证据，首页/console/指南同步；人工GUI不由Codex冒充，PR不自动合并。

## 2026-11-07 A代码复审结论

V11-A当前远端PR #7包含真实五表/API/前端、17JUnit和155条Codex HTTP证据（本轮ChatGPT未独立执行），旧push403是历史记录。发现两项B前必须收口的P0：已结束的本月Free与新的不重叠本月Pro可能顺序订阅，违背月度套餐冻结；MySQL `connectionTimeZone=UTC`未强制Hikari池连接会话`time_zone=UTC`，真实非UTC配置风险。先完成专用审查修复与隔离MySQL重测，负责人产品真人验收及V1前置收口不省略。完整报告和任务见上方链接。
