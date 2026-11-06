# FlowGate 功能块 2｜交给本地 Codex 的预备提示词

> 设计时的阶段门槛保留如下；负责人已于 2026-11-06 明确授权“可以开始了”。R1缓存恢复已修复并补定向测试，块2实际进度见任务书第12节；授权开始不代表最终审核通过。

## 一、先核查，再进入实现

1. 在本地 FlowGate 仓库运行 `pwd`、`git status -sb`、`git remote -v`、`git log -1 --oneline`，保护用户已有修改，不得 `reset --hard` / `clean -fd` / 强推 / 修改凭据。
2. `git fetch origin`，核对 `feat/block1-control-publish` 的真实 SHA 和是否已被审核/合并；核对审核后稳定基线。**不能直接从旧 main 创建实现分支**。
3. 阅读 `docs/reviews/block-1-static-review-2026-11-06.md` 并先确认缓存失败恢复 R1 已在 Block1 修复或已取得可重复验证结论。若未解决，停下报告，不能以 Block2 限流代码绕过。
4. 阅读 `docs/progress/block-2-traffic.md`（唯一业务/算法/数据契约）、`docs/standards/test-console-ux-v2.md`（强制 UX 合同）、`docs/guides/test-console-guide.md`（现有五区真实操作）、`docs/standards/frontend-sync-rule.md`（历次功能同步）。
5. 在用户授权且前置复核通过后，基于 Block1 经确认的稳定提交创建 `feat/block2-traffic-governance`。如分支已存在，先确认分支状态；不要从错误的旧版 main 复制代码。

## 二、固定顺序（不要重新设计架构）

1. **先修测试台体验和帮助**：五步操作引导、独立响应卡、版本状态、常见 HTTP 错误、真实使用指南入口；保留全部块 0/1 可用按钮；纯 HTML/CSS/JS，不加 npm/Vue/React。
2. Control 在现有表与 API 上实现 RATE_LIMIT/CONCURRENCY 的发布校验，并把 ROUTE 数字 ID 正确翻译成快照中的 targetRouteId；保持历史版本不可修改和 checksum 一致。
3. Gateway 根据当前已匹配 Route 的**同版 metadata** 解析有效策略，先认证再准入；不能在请求中读取控制面 MySQL。
4. Redis Reactive 单 Key Lua 令牌桶，使用 Redis TIME、定点整数、TTL、独立规则 revision；逐层 GLOBAL→SERVICE→API→ROUTE→CALLER，明确多维非全原子语义。
5. 本机服务/路由在途并发许可，非阻塞 tryAcquire + Reactor doFinally；异常、取消、超时和中途失败要准确释放，不能声称跨网关全局并发。
6. 通过真实 429/503、指标与短热点对照证明行为，保留实际脚本和结果；不做长时间最终压测。前端策略页面同步标注哪些已生效、哪些留给 Block3。

## 三、最少技术与编码规范

- 维持 Java17、Boot4、Gateway WebFlux、MVC Control、MyBatis-Plus、Reactive Redis/Lua、Micrometer；新增依赖必须证明当前不可替代。禁止引入 Kafka、Redisson、Sentinel、Nacos、前端构建链或额外微服务。
- 使用**现有参数名称** `capacity/refillPerSecond`、`maxConcurrent`；不能让 Codex 自行改成旧文档的同义名称。
- 关键 Java/Lua 和前端流程使用中文注释，复杂块按照 `// 1. ...`、`// 2. ...`、`// 3. ...` 或 Lua `-- 1. ...` 分点。
- 完整模块不做无意义多层抽象；只执行本块新增、修改、直接受影响范围的定向测试；共享契约的直接调用者必须一起验证。
- 所有 Token/密码/内部 Key、`.env`、临时数据、真实日志不得提交仓库；测试前检查 Docker/端口和未提交修改，不破坏用户现有服务。
- Conventional Commits，按功能块提交到独立分支，记录实际 SHA。遇到编译/数据兼容问题先最小修正并准确回填，不擅自修改业务方向。

## 四、真实验收输出（完成前绝不能勾选）

- [ ] 前端五步向导和中文指南同步完成，并通过真实浏览器操作
- [ ] 快照中 ROUTE 绑定正确、生效标识可信、历史 checksum/回滚兼容
- [ ] Lua 单 Key 并发/TTL/时间回拨与返回类型测试
- [ ] 多维扣减、Caller 作用域、未认证优先、Redis 故障 fail-closed 测试
- [ ] 本机并发取消/异常/部分获取失败时许可无泄漏
- [ ] 真实 429/503 不进入下游，指标真实可用
- [ ] 两个 Gateway 实例共用 Redis 限速小验证
- [ ] 短热点 A/B 对照及原始结果、实验局限说明
- [ ] 更新 `docs/progress/block-2-traffic.md` 第 12 节与 README、前端使用指南
- [ ] 停留在 Block2 分支等待负责人/ChatGPT 审查，不合并 main、不启动 Block3

**你是负责实现的本地 Codex，不需要重新做开放式技术选型；遇到无法安全执行的地方按具体文件、类、错误与最小替代方案报告，不凭空宣称测试通过。**