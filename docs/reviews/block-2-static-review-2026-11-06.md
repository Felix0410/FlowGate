# 功能块 2｜2026-11-06 远端源码静态审核

> 审核对象：`Felix0410/FlowGate`，`feat/block2-traffic-governance`，最初审核 HEAD `f1772782db60afd746b7e7c9435fd81a0895f223`。
> 核查手段：GitHub 远端源文件、测试用例、脚本与阶段进度记录。**未在本次 ChatGPT 环境里运行 Maven、MySQL、Redis、Docker 或真实浏览器。**
> 结论：Block2 已有实质代码、自动化测试与短热点对照记录，具备代码复审条件；**未达到全部验收完成状态**，保留草稿 PR，待下列定向复核与负责人手动体验。

## 1. 当前代码与测试证据

| 模块 | 静态核查结果 |
|---|---|
| 速率限流 | `ReactiveTokenBucketLimiter` 通过 Reactive Redis 执行单 Key Lua；脚本用 Redis TIME、整数定点计数与有界过期、返回三个数字 |
| 多维策略 | `PolicyRules` 校验及 `EffectivePolicyResolver` 通过同版本 Route metadata 固定范围与策略参数；CALLER 规则需认证主体匹配 |
| 在途并发 | `LocalConcurrencyLimiter` 的 CAS 获取与 Lease 幂等释放，过滤器 `doFinally` 覆盖 Reactor 正常/错误/取消路径 |
| 网关准入 | `CallerAuthFilter`(-100) 先于 `AdmissionGlobalFilter`(-90)，429 RATE_LIMITED / 503 CONCURRENCY_LIMITED、ADMISSION_UNAVAILABLE，拒绝不走下游 |
| 发布兼容 | `ReleaseService` 延续 schemaVersion=1，绑定增加 `targetRouteId`，旧 PENDING 规则不被静默启用 |
| UI 改进 | 原生 HTML/CSS/JS 中已有导航、五步说明、辅助模板、策略表单、独立结果、10 条脱敏摘要、手册链接 |
| 实验 | `scripts/block2-smoke.py`、`block2-hotspot.py`、`docs/progress/evidence/block2-hotspot.json` 及对应小规模测试代码存在 |

Codex 在 `docs/progress/block-2-traffic.md` 中记录：**24 个 JUnit 通过**、真实隔离 MySQL/Redis + 两 Gateway 冒烟，以及 A/B 各 15 秒实验；A fast 成功 14 / P95 2007.691ms，B fast 成功 146 / P95 7.13ms。**这些数值是 Codex 报告的本机测试结果，本次静态复核没有独立复现。** 短实验不是正式压测，A 组 fast 仅 14 样本，不能推广为生产性能收益。

## 2. 需要收口的项目

### R1｜高级页面的“编辑已有策略”与绑定目标语义（明确可见的不一致）

`app.js` 中 `policy-existing` 选择已有策略后，`policy-save` 允许操作员继续选择新的 `policy-target`，但代码的 `if (!id)` 分支仅在**新建策略**时调用 `/admin/policies/{id}/bindings`；编辑已有策略时用户更改的目标不会保存，也没有文字阻止其误解。

- 最小修复：编辑已有策略时禁用目标切换并解释“只可修改参数/启停；绑定关系不随之变化”；或在服务端契约允许前提下提供单独真实绑定操作并核验重复绑定，**禁止在 UI 上伪装已改目标**。
- 增加一条定向前端测试：选择已有策略、变更目标后不会显示“绑定已更改”，或禁用选择并有说明。
- 建议顺便确认“已发布但节点未应用”的提示足以从首屏找到，不依赖阅读代码。

### R2｜刷新超时/取消/迟到事件的最终路由缓存一致性（高优先级持续观察）

`SnapshotPollingService` 已添加失败时 `cache.refresh()` 恢复、应用互斥及迟到事件补偿；`SnapshotSwitchTest` 新增真实缓存超时/迟到写入用例。这属于对 Block1 关键风险的实质修复尝试。

但取消路径 `doFinally(CANCEL)` 中使用独立 `subscribe()` 异步执行恢复，随后立即释放 `applying` 标记；另一个新快照可能开始应用而恢复请求仍在进行。迟到事件处理也可能异步触发新的 `cache.refresh()`。**这是静态可见的竞态窗口，不等于已证实被利用或已复现故障。**

- 补充可控交错用例：V2 应用取消/恢复尚未完成时新 V3 拉取 → 延迟恢复完成；断言最终缓存与 `store.applied` 一致，不能被过期恢复覆盖。
- 如果可复现，要求恢复与应用序列化或引入明确的刷新所有权/版本校验；避免循环事件补偿造成不停刷新。不在本轮发明配置中心或大型新框架。
- 保留 Block1 既有成功及失败用例复测证据，确保新修复不造成路由无法推进。

### R3｜UI 的真人体验与账号权限验收尚未执行

Codex 明确说明 `node scripts/block2-console-test.js` 是模拟 DOM 的脚本，**不是实际浏览器操作**。此前负责人反馈前端易用性差，因此以下验收不能豁免：

- 本机启动后，按 `docs/guides/test-console-guide.md` 不读源码完成“检查→登录→初始化→发布→节点生效→限流/并发演示”；
- 至少在普通桌面和窄屏看布局、键盘操作、焦点/禁用提示，以及 VIEWER/OPERATOR/ADMIN 对应按钮与实际权限一致；
- 实际发送 429/503，确认每次操作就地显示清晰中文解释，且 Token/密码不出现在静态日志、最近 10 条摘要或持久化存储；
- 对不合理的“既有草稿/版本”场景提供明确引导，不把用户引到重复创建/409 错误循环。

### R4｜GitHub 交付记录应区分历史推送故障和当下真实状态

Codex 阶段文件 `12.4` 中“HTTP 403，未推送成功”描述的是**当时运行该命令的原始输出**。远端现已包含 Block2 实现分支和 `f1772782...`，因此它不应作为当下远端仅有文档的判断。新增审查记录，保留原始故障证据，不伪造远端已跑 CI，也不擅自修改 main。

## 3. 下一步门槛

1. 代码/真实环境完成 R1 与 R2 所需的最小修复或可重现实证；
2. 负责人完成至少一次 R3 人工浏览器可用性验收，发现的问题回到 Block2 分支处理；
3. Codex 在 Block2 进度文件追加真实新 SHA、复测命令和结果；PR 保持 Draft 直至审核；
4. 核对 stacked PR 关系（Block1 PR #1 → Block2 设计 PR #2 → Block2 实现 PR），基线不混乱、不直接合并 main；
5. Block2 审核通过后才准备/授权 Block3 故障保护与监控实现。完整性能验收仍保留到项目最后。

**本次核查未运行本地测试，不宣称完成 Block2 最终验收。**
