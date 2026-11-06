# 功能块 3｜2026-11-06 GitHub 远端源码复核与综合验收门槛

> 审核对象：`Felix0410/FlowGate`，`feat/block3-resilience-observability`，提交基准 `a460efc2feb7b83116fab20afd0bd2c9739df663`；PR #4。
> 审核性质：GitHub 远端源码、阶段记录、JUnit/Node 测试源码、HTTP 原始证据与业务前端静态核查。**ChatGPT 本轮没有独立运行本地 Maven、Docker、Java 服务或真实浏览器**。
> 审核结论：**Block3 主要业务闭环已实现，源码静态审查未见需要推翻架构的阻断项，可以准备最终综合验收方案；Block3 尚未满足最终验收条件，PR #4 保持 Draft，main 不变。**

## 1. 已确认的代码实现

| 内容 | 源码位置 | 结论 |
|---|---|---|
| Block2 遗留：已有策略目标误导 | `test-console/app.js`、`block2-console-test.js` | 编辑已有策略目标已锁定，解释“只改参数/启停”；定向自动化检查已有 |
| Block2 遗留：取消刷新/恢复交错 | `SnapshotPollingService`、`SnapshotSwitchTest` | 恢复期间持有 `applying`；新增 Sinks 可控交错用例 |
| TIMEOUT 受控参数和旧版兼容 | `PolicyRules`、`ProtectionPolicyResolver` | 50..10000ms，SERVICE/API/ROUTE 最小值；旧 PENDING 不自动执行 |
| Gateway per-route 超时与错误映射 | `SnapshotRouteDefinitionLocator`、`ProtectionResponses` | 官方 metadata，主动超时 504、可信 Request ID、非正常响应不伪造 200 |
| Resilience4j 熔断 | `CircuitBreakerConfigRegistry`、`ProtectionTest` | 使用官方配置与 Gateway CircuitBreaker Filter；稳定 policy/revision/route 命名，OPEN/HALF_OPEN 有定向测试 |
| GET-only 降级 | `ProtectionResponses`、`PolicyRules` | 仅 GET STATIC_JSON，503 DEGRADED + degraded=true，内部 fallback 直接访问拒绝 |
| 可观测性与节点摘要 | `ReleaseController`、`AdmissionMetrics`、`CircuitBreakerConfigRegistry` | 受限节点报告、ONLINE/STALE 派生、低基数保护指标；运行态内存摘要非强实时 |
| 可控 Demo 与前端 | `DemoFaultController`、`static/test-console/`、`docs/guides/` | 默认关闭故障 API；原生导航、业务表单、50 个输入提示及离线手册；无重型前端构建链 |

已检视 `docs/progress/evidence/block3-smoke.json`：`passed=true`、192 条请求，状态码分布精确为：200×122、201×30、400×5、401×4、403×3、404×3、429×1、500×7、503×11、504×6。字段未见密码、Token 或 Authorization，包含 Path、status、Request ID、必要节点/指标摘要。**这只证明记录文件与阶段报告一致，不代替本轮独立运行。**

Codex 阶段记录：34 个 JUnit 用例通过（Contract7、Control1、Gateway22、Demo4），Node 模拟 DOM 契约和隔离 MySQL/Redis、两个 Gateway 的真实 HTTP 脚本通过。该结果仍需在最终阶段的统一测试中复现。

## 2. 待验收及改进项

### A1｜负责人手动体验是必经门槛

负责人曾明确反馈“前端可用性太差且没有使用指南”，当前源码已实质补充业务导航、`guide.html` 和详细帮助，**但没有实际浏览器桌面/窄屏/键盘体验证据**。建议负责人在打开 `http://127.0.0.1:8081/test-console/` 后，按 `guide.html` 无需看 Java 源码完成：

1. 首次环境探测/登录→建立 Demo 草稿→发布→节点应用→fast 200/echo 201。
2. 新建 RATE_LIMIT 和 CONCURRENCY，观察真实 429/503，检查“已保存草稿”和“节点已应用”的不同状态。
3. 绑定 TIMEOUT=200ms 的 slow，500ms 请求得到 504；绑定 fault 的 CB，真实 500→OPEN 503（Demo 命中不增长）→HALF_OPEN→恢复；GET 降级显示 degraded=true。
4. VIEWER/OPERATOR/ADMIN 不同权限，旧策略只能编辑参数/启停；版本冲突 409、回滚、会话过期/清空。
5. 检查桌面/窄屏布局、键盘 Tab/禁用原因、跳转到中文手册并返回业务页面、输入字段帮助、反馈是否就地可读。发现问题继续在 Block3 分支修复后复核。

### A2｜真实跨阶段统一回归尚未执行

本块自动化是“变更模块与直接依赖”的定向测试；最后验收须单独运行完整工程 JUnit 与真实链路：Block0 代理与 Trace → Block1 认证/发布/CAS/回滚 → Block2 分级 Redis 与本机许可 → Block3 超时/CB/降级/故障恢复。所有旧快照/权限/策略组合均不能误执行、误降级或绕过鉴权。

### A3｜长期运行和观测边界

1. `CircuitBreakerConfigRegistry` 的注册表按参数 revision 持有历史 breaker 实例；真实进程长期频繁发布可能积累实例和事件标签，当前报告**明确没有长期容量/实例淘汰验证**。最终阶段至少做小范围高频版本切换和实例数/内存观察，不能声明生产级长期稳定。
2. Control 的 CB 运行摘要仅内存保存，Control 或 Gateway 重启可显示未知/状态重置，节点 `lastSeenAt` 反映最近一次报告而非强一致实时状态；这是已明确的设计限制，不应把页面状态宣传为无延迟事件流。
3. Gateway 取消/超时释放本地并发许可，不代表 Demo Servlet 阻塞线程必然立即终止。最终实验须分别记录“网关完成时间”和“下游资源实际占用”，避免误解。
4. Block2 热点短实验只有一组 15 秒 A/B，A 快速请求样本很少；不能作为正式三组性能压测或生产收益证明。最终另作直连/只代理/启用治理的可重复对照。
5. 当前 `main` 仍为 Block0 基线，PR #1/#2/#3/#4 形成叠加结构。**不得跳过顺序直接合并 PR #4**；需要在所有 PR 完成审核后统一梳理基线和合并策略。

## 3. 下一阶段建议

**建议进入“最终综合验收方案准备”，不等于验收测试已开始或通过。** 最终验收清单应单独在仓库维护，包含完整项目 build、真实业务场景、历史发布兼容、两 Gateway、故障恢复、性能三组对照、监控/敏感信息、前端实际浏览器体验、数据隔离及最终 Git 合并策略。

在用户同意最终验收执行前，可以继续准备文档与必要脚本，但不提前运行长时间压测、不修改用户原有 Docker 数据库、不合并 main。人工 UI 反馈应先在 Block3 分支整改；尚未取得负责人手动验收证据，不把本块状态写为“已验收完成”。

## 4. 静态审核结果状态

- [x] 确认远端 Block3 有实质 Java/前端/脚本代码（不是仅任务文档）
- [x] 核对关键认证→准入→保护→代理错误语义与历史 PENDING 兼容源码
- [x] 核对 192 条 HTTP 证据文件数量/状态码和报告一致
- [x] 核对 Block2 R1/R2 代码与 JUnit/Node 记录
- [ ] ChatGPT 独立运行 Maven/隔离 HTTP 测试（本轮未执行）
- [ ] 负责人真实浏览器/窄屏/键盘体验及反馈修复
- [ ] 最终全项目回归、正式性能三组对照、较长故障/实例容量演练
- [ ] PR 审核、合并至 main 并保留可复现版本标签

**当前阶段：Block3 代码可进入下一轮人工验收，不能宣告整体项目完成。**
