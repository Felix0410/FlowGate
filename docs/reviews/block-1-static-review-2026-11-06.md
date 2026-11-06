# 功能块 1｜2026-11-06 远端源码复审与阶段交接

> 审核对象：`Felix0410/FlowGate` PR #1，`feat/block1-control-publish`，审核时 HEAD=`ad4e5bc8754933ca3a84076dae13f60de91a0f97`。
> 审核方式：GitHub 远端代码、原生页面、测试源码、进度及 README 静态核查。**没有执行本地 Maven、Docker、浏览器联调或负载实测。**
> 审核结论：功能块 1 **主要实现已经远端交付，可以准备 Block2 的详细设计；PR #1 保持 Draft，尚需修复/验证及负责人手动验收，不建议直接合并 main。**

## 一、确认实现

| 能力 | 源码证据 | 结果 |
|---|---|---|
| WebFlux Gateway 与 MVC Control 分离 | `flowgate-gateway`、`flowgate-control`、独立 POM | 源码确认 |
| 10 张业务表及初始化 | `sql/flowgate-init.sql` | 源码确认；可重复执行以 Codex 实测记录为依据 |
| opaque 管理/Caller 会话、Redis digest、BCrypt | `IdentityService`、`AdminAuthInterceptor`、`BootstrapAdmin` | 源码确认 |
| MySQL CAS 发布、不变快照、SHA、回滚 | `ReleaseService`、`SnapshotCodec` | 源码确认 |
| 快照轮询、动态路由、同版权限 | `SnapshotPollingService`、`SnapshotRouteDefinitionLocator`、`CallerAuthFilter` | 源码确认 |
| 请求入口可信身份头清理 | `RequestIdWebFilter` | 源码确认 |
| 本地部署 | `compose.local.yml`、`scripts/run-local.py`、`.env.example` | 源码确认；运行实况来自 Codex 记录 |
| 简易测试台 | `flowgate-control/src/main/resources/static/test-console/` | 存在 5 区真实 fetch 操作，易用性不足 |
| 单测和联调 | 代码中 Gateway/Contract/Demo 测试与 `scripts/block1-smoke.py` | Codex 记录 10 个 JUnit 用例、6 组集成冒烟通过；未独立复现 |

审核期间 GitHub 的 PR #1 明确显示 **71 个变更文件，9 个提交，HEAD 为上述 SHA**；因此旧进度表所说“推送 HTTP 403，远端分支仅有文档，未建 PR”已是**历史情境而非当前状态**。不要删除 Codex 原始日志结论；应追加新核查日期说明交付状态变化。

## 二、需在功能块 1 关闭前处理的项

### R1｜待补的动态路由刷新失败/超时恢复验证（优先）

当前 `SnapshotPollingService.apply()` 在 `RefreshRoutesResultEvent` 完成后才 `store.confirm(next)`，失败时 `store.restore()` 仅回滚 `candidate` 引用。若官方 `CachingRouteLocator` 已将候选路由放入缓存，但确认事件迟到、比对失败或超时，**需要证实并确保缓存本身也回到最后有效路由**。现有 `doesNotReportPendingOrFailedRefreshAsApplied` 测试覆盖“未误报 applied”，没有覆盖“失败后缓存实际路由仍是旧版”及迟到异步事件。

要求 Codex：
1. 增加最小可控定向测试，构造候选 V2 已进入缓存但应用确认失败/超时、随后迟到成功事件的顺序；
2. 断言失败后请求实际匹配与鉴权仍是最后确认 V1，节点不误报 V2；下一次正常发布 V3 仍可成功；
3. 如果复现缓存和 applied 状态不一致，用 Gateway 官方刷新能力做最小补偿/串行化，修复后复测。此项影响后续 Block2 同版限流可靠性，不能只改文档绕过。

### R2｜当前前端可用性与帮助（必做，但可纳入 Block2 首项）

前端 A/B/C/D/E 混在长页面，一套底部响应面板反复覆盖结果；`expectedVersion=0` 默认易造成 409；JSON 高级编辑/权限/回滚缺少步骤引导。用户已明确反馈“可用性太差且没有使用指南”。

处理约定：
1. **当前可立即使用**的操作说明已在 `docs/guides/test-console-guide.md` 编写，注明对应 Block1 五区旧版；
2. Block2 必须优先实现 `docs/standards/test-console-ux-v2.md` 的五步引导、独立结果卡、版本状态、错误解释和帮助入口；
3. 仍使用原生 HTML/CSS/JS，不增新的前端技术栈；必须保持 Block0/1 全部真实操作，并由负责人做手动可用性验收。

### R3｜交付与验收事实更新（必须）

- 在 Block1 进度文件追加“2026-11-06 远端审查”：代码已在 PR #1 分支、真实 HEAD，PR 为 Draft，尚未合并 main；之前本地 403 记录保留为历史。
- PR #1 的说明当前仍是“只有文档，没有代码”的创建时文本，需要更新为当前真实状态。
- 负责人手动测试尚未获独立验收记录；Block1 不能凭 Codex 自述自动改成“已验收”，更不能自动开启 Block2 编码。

### R4｜本块遗留测试覆盖说明（建议）

- Control 的登录、MySQL 发布事务、授权及回滚主要来自脚本真实联调记录，尚未在仓库中看到对应 Controller/Service 的独立 JUnit 或可在 CI 使用的轻量事务测试；后续改相关功能应补最必要的定向用例，不要求本轮全面重写测试。
- 明确管理登录无生产级防暴力破解、控制面默认仅绑定本机；不可直接部署公网。公网、多 Gateway、正式性能/长期故障压测仍在后续验收。

## 三、Block2 设计需依照的实际契约

- 参数统一使用现有 `RATE_LIMIT:capacity/refillPerSecond`、`CONCURRENCY:maxConcurrent`，禁止沿用旧 Block2 文档的 `ratePerSecond/burstCapacity`。
- 当前 `fg_policy_binding.target_id` 是数据库数字，而 `RouteSpec` 只携带 routeId；必须在 Control 发布快照时映射后冻结 metadata，避免网关热路径读数据库或跨版本取规则。
- 目前已发布策略只有 `executionStatus=PENDING_BLOCK_2_3`，不能被新网关自动假装已执行；新版本明确标记、保留旧快照 checksum/回滚。
- Redis Lua 单 Key 原子限速与本地在途并发属于两个不同能力，不夸大多 Key 原子性、多实例全局并发或热点隔离性能收益。
- 前端能力、定向测试、进度回填必须随 Block2 的后端实现一同完成。

**执行边界：** 本文是审查证据和下一阶段设计前置条件，不是批准修改现有生产配置或跳过 Block1 人工验收。