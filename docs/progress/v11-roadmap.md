# FlowGate V1.1 开发路线与真实状态

更新：2026-11-06。此文档属于设计提案，**所有 V11-A～D 均未实现、未运行、未验收**。

## 为什么要做这次业务调整？

旧 FlowGate 能完成统一路由、Caller 认证、动态策略、五维限流、并发保护、熔断恢复及性能实验，但作为“通用网关”的产品独特性弱。V1.1 不重造代理内核，而是在现有架构上增加**第三方开发者 API 套餐与累计准入配额**业务，使调用凭证、额度、用量、失败恢复、监控和数据一致性有真实的业务关系。

## 固定交付顺序

| 序号 | 文档 | 目标 | 当前 |
|---|---|---|---|
| 前置 | docs/progress/block-4-acceptance.md | V1 后台手测、完整回归、PR 审核 | 待负责人收口 |
| A | docs/progress/v11-a-business.md | 客户、应用、套餐、订阅、凭证入口占位、产品 UI | 未开始 |
| B | docs/progress/v11-b-quota.md | 可信计量准入、正式应用凭证/隔离、MySQL 原子扣额、幂等 Ledger 与 Outbox | 未开始 |
| C | docs/progress/v11-c-async.md | Kafka、可靠投递、幂等消费、用量查询与对账 | 未开始 |
| D | docs/progress/v11-d-acceptance.md | SQL 大数据实验、四组性能、双节点故障、产品验收与发布 | 未开始 |

- 业务与技术总设计：docs/design/openapi-platform-v1.1.md。
- Codex 即刻阅读指令：CODEX_V11_OPENAPI_PROMPT.md；只能做 A 阶段（且 V1 基线安全检查通过后）。
- 原有 /console/ 作为正式后台继续迭代、/test-console/ 只作为技术验证入口。每阶段同步 UI、指南与进度。
- 每阶段的真实执行日志和 Git SHA 以后按节追加，不提前勾选。完成 D 之后才申请审核是否合并，新功能绝不自动合并 main。

## 简历与演示的诚实边界

旧 V1 的测试结论来自 docs/acceptance/final-results.md；V1.1 规划不能写成“已实现 Kafka 账本、千万级 SQL 优化”。本设计有两项不可随意放大的限制：
- 月度额度授予要经 Control/MySQL 权威事务，带来额外 HTTP/DB 延迟与故障依赖；这是以真实性和可恢复性换性能的取舍。
- 配额是“请求准入授权单位”，不是“成功响应计费”或商业金额；后端失败后不自动退还，异步 Kafka 用量不能拿来决定扣量。
