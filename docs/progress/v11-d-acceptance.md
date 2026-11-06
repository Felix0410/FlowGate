# FlowGate V1.1-D：SQL 优化、正式产品验收、性能实验与交付（Codex 编码合同）

> 状态：未开始；前置 A、B、C 均已通过定向验证。V1 的旧 Block4 证据应保留，**不能把 V1 纯代理性能冒充 V1.1 计量性能**。

## D-00 交付目标

完成“开发者应用 → 套餐订阅 → 显式计量 API → 权威配额准入 → 真实代理 → 异步统计 → 用量对账 → 管理员治理/回滚”的全链路证据；确认性能、故障恢复、安全边界和产品后台可用。保持 V1 的 Gateway 纯代理/治理能力完整。

## D-01 SQL 查询优化

1. 针对 fg_quota_ledger、fg_usage_daily、fg_audit_log 与 fg_outbox_event，实现按 app、API、UTC 日期、事件状态的后台查询；不提供 unbounded SELECT *；最多 100 条/页，使用稳定的 (created_at,id) 或 (period,id) 游标，支持同时间戳稳定顺序、翻页无重复/遗漏。
2. 创建独立脚本在**隔离数据库**生成 10万+ 行无敏感模拟账本/审计；不要污染用户本地已有环境，不使用 DROP 原项目表。以 EXPLAIN ANALYZE/真实计时比较优化前后扫描行、延迟、索引空间与写入影响，记录相同数据/SQL/资源条件；结果不许提前杜撰。
3. Index 由查询语句推导，减少宽联合索引数量；评估 count 总行数耗时和 offset 深页性能；禁止误把数据库 SQL 优化说成加 Redis 缓存就完成。

## D-02 四组对照与故障

A DIRECT：直接请求 Demo；B PROXY：经 Gateway 且无 RATE/CONCURRENCY/USAGE；C GOVERNED：启用现有 Redis 多维速率/并发保护；D METERED：在 C 上增加显式月度 quota 服务调用（测试套餐额度需充足，不让额度耗尽干扰性能对照）。

1. 统一负载请求组合，优先分出 Query、Slow、Compute/DB-Heavy，控制总到达速率、客户端最大 in-flight、warmup、运行顺序和两轮以上样本。
2. 记录各组计划/实际到达、CLIENT_SHED、成功 RPS、业务成功 P50/P95/P99、429/503/504 与 quota 特有错误、Control 内部 quota p95、MySQL 锁等待、Kafka lag、Gateway/Demo/Control CPU/RSS。
3. Redis/Control/MySQL/Kafka 各自在真实隔离环境注入故障：验证计量 fail-closed、非计量路由按旧语义运行、Outbox 不丢、恢复后对账一致。
4. Breaker 历史实例积累、Gateway 取消/timeout 许可归还等旧限制复核，报告残留风险；不做“线上百万 QPS”的无根据承诺。

## D-03 产品验收与交付

- 用户不看源码，在 1280px PC 和 375px 手机页面独立完成：创建开发者→应用→订阅→Caller Token→计量 API 发布→5 次准入/第6次拒绝→查看账本/异步用量→下月套餐变更→重放失败事件；需有清晰中文帮助和 401/403/409/429/503 反馈。
- 一次性 Token 在 UI、日志、截图中不得泄露，VIEWER/OPERATOR 权限真实验证；旧 /test-console/ 保留。
- 最终使用 Java17 mvn verify、Node 两类产品契约、Python 真实 HTTP 测试、隔离多实例 MySQL/Redis/Kafka 联调，报告实际退出码和通过用例数。先核对用户 IDE / Docker 环境，不能自动销毁旧卷或擅自重启原服务。
- 最终说明写 docs/acceptance/v11-final-results.md，包含源码 SHA、依赖版本、环境、运行命令、首次失败/修复、未运行项目、压测原始脱敏样本与可重现命令。
- 每阶段 UI 页面与指南同步；产品 /console/ 是正式入口，测试工具只保留开发用途；全部新增 Java 关键逻辑使用中文编号注释。
- 用户另行明确授权才审核/合并新 PR 与既有 stacked PR；不要自动合并 main、创建“已上线”标签。

## D-04 最终通过标准

真实双网关并发扣减不超额，Kafka 停机/重复后的账本-用量收敛，SQL 改善有真实数据，四组压测包含计量额外开销，核心非计量路由全部回归，负责人浏览器体验认可，并列出所有已知限制。缺少任一项一律标注“未完成/待验收”，不可把单测通过误写为项目已交付。
