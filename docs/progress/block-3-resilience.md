# FlowGate 功能块 3｜超时、熔断、受控降级、故障恢复与可观测性（实施任务 V2.0）

> 日期：2026-11-06。状态：**实现与定向验证已完成，等待复审；负责人真实浏览器/最终全量验收未执行**。
> 工作分支：`feat/block3-resilience-observability`，基于 `feat/block2-traffic-governance` 的远端实现建立，不从旧 `main` 开始。
> 目标：完成 B09、B10、B12 的可靠性闭环，并保留 B01–B08 已交付的全部业务/前端功能。
> 停止边界：本块定向测试后等待审核；**最终完整回归、正式性能压测、生产部署审查在末阶段**，不可提前宣称全部完成。

## 0. 开始编码前：已有 Block2 遗留必须先收口

本块直接依赖 Gateway 缓存版本一致性、准入许可释放和控制台版本状态。先读 `docs/reviews/block-2-static-review-2026-11-06.md` 及 PR #3，完成两个必要的最小修复，再动保护逻辑：

1. **前端已存在策略绑定目标误导（R1）**：`app.js` 的已有策略编辑允许选择新目标，但 `policy-save` 只在新建时绑定。最小方案：编辑时锁定目标并提示绑定关系不随参数修改而改变；保留原来的单独绑定 API 入口；加定向 JS 测试。不允许按钮给出“目标已更新”的假结果。
2. **路由缓存补偿与新版本应用的竞态（R2）**：`SnapshotPollingService` 取消路径以独立 `subscribe()` 恢复缓存，但 `applying` 状态可先释放。补一个可以控制交错的 JUnit：V2 取消→旧恢复悬挂→V3 成功→旧恢复完成；验证最终真实缓存与 `store.applied` 一致、不会倒退 V2/V1，也不能假报节点版本。若复现，最小方式串行化恢复与下一次应用；不得用 sleep/增加轮询时间遮盖。
3. 原有 Block1/Block2 PR 保持 Draft，负责人过去要求不使用电脑自动化界面控制；Codex 继续执行自动测试，但**真实浏览器人工操作由负责人执行**。本次“进入功能块 3”是编码授权，不等于旧 PR 已合并或全部人工验收通过。
4. 两项补修在本块分支独立做 `fix(block2): ...` 提交，并在 `docs/progress/block-2-traffic.md` 补记 SHA、真实命令/结果。若无法确认安全的路由缓存版本，**停止新熔断接入并报告阻塞**。

## 1. 不重新选型：实际代码基线

- Java17 / Spring Boot 4.0.9 / Spring Cloud 2025.1.4 / Gateway Server WebFlux 5.0.4；`flowgate-control` MVC+MyBatis-Plus+MySQL+`StringRedisTemplate`，`flowgate-gateway` Reactive Redis+Gateway+Micrometer。
- 已有 `Snapshot` 顶层 `schemaVersion=1`，`routes[]` 中 `RouteSpec`（包括 `configVersion`），`policies[]`、`bindings[]` 以 Map 白名单字段承载。Control 使用 MySQL CAS 发布不可变快照、SHA 校验并允许回滚为更大版本。
- `fg_policy` 现有 `TIMEOUT` 参数**恰为** `{"timeoutMs":1000}`；`CIRCUIT_BREAKER` 参数现有**四个**：`failureRateThreshold`、`slidingWindowSize`、`minimumNumberOfCalls`、`waitDurationMs`。不能无故把已保存草稿重命名。数据库、`/admin/policies`、`/admin/policies/{id}/bindings`、`/admin/releases/publish` 接口继续复用。
- `PolicyRules.ENFORCED="ENFORCED_BLOCK2"` 仅驱动 RATE_LIMIT / CONCURRENCY；TIMEOUT / CIRCUIT_BREAKER 在 Block2 快照仍为 `PENDING_BLOCK3`。**旧历史 PENDING 不得因网关升级而自动执行**；Block3 新发布才标为 `ENFORCED_BLOCK3`。
- Gateway `CallerAuthFilter` order=-100（认证/Scope）；`AdmissionGlobalFilter` order=-90（速率→本地在途许可→`chain.filter`），许可按 Reactor `doFinally` 释放。新增保护必须在这些业务决策之后、真实下游前工作，并通过真实 Gateway Filter 链验证顺序。
- `SnapshotRouteDefinitionLocator` 按快照生成官方 `RouteDefinition`，`EffectivePolicyResolver.freeze()` 在 route metadata 冻结 Block2 生效策略。不引入 DB 查询/同步 I/O 至 Netty，请求不从 `store.candidate` 读取“最新策略”。
- Gateway 当前 `application.yml` 只开放 health、metrics 和本地 CORS；不能开启 Actuator 的可写 `gateway` 动态路由管理端点。

**仅新增一个有必要的运行时依赖**：在 `flowgate-gateway/pom.xml` 加 `org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j`（由现有 Spring Cloud BOM 管理版本），使用 Gateway WebFlux CircuitBreaker GatewayFilter Factory / Reactor Resilience4j。官方参考：[Gateway WebFlux CircuitBreaker](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/circuitbreaker-filter-factory.html)、[路由级 HTTP timeout](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html)。实际 API/Filter 顺序须以项目**精确 Jar 版本编译、定向测试**确认。不要引入 WebMVC Gateway、Sentinel、Hystrix、Nacos、Kafka、ELK、Grafana、全套 OpenTelemetry、重型前端框架；不自研熔断状态机。

## 2. 完成业务闭环与责任划分

管理员在现有控制台选择一条可控 API（例如 `demo-slow` 或 `demo-fault`）→ 用原策略 API 保存/绑定 TIMEOUT 或 CIRCUIT_BREAKER 草稿 → CAS 发布版本 Vn → Gateway 校验并**确认实际路由集已切换** → 对慢/故障上游触发超时 504、熔断短路 503 或显式降级 → 观察 CLOSED→OPEN→HALF_OPEN→CLOSED，确认 OPEN 时请求不再到达 Demo → 停止故障后恢复 → 查看指标、版本/节点最后上报时间和请求 ID → 对旧配置回滚，新版本仍可正常代理并保持认证、速率与许可语义。

模块落点（可按实际职责少量合并文件，不得过度拆层）：

| 现有模块 | 限定新增职责 |
|---|---|
| `flowgate-contract` | `PolicyRules` 扩展 Block3 的超时/熔断参数、scope 与唯一性校验；不另造 schema V2 |
| `flowgate-control` | `CatalogService` 保存校验、`ReleaseService` 发布 `ENFORCED_BLOCK3`；可在现有 Release/Status Controller 最小扩展只读状态；保留审计 |
| `flowgate-gateway/routing` | 将同版策略转化成 Gateway 官方 Route metadata（超时）及 `CircuitBreaker` FilterDefinition；保留原有 SetPath/Scope/准入 metadata |
| `flowgate-gateway/protection` | 至多两个薄适配类：`ProtectionPolicyResolver`、`CircuitBreakerConfigRegistry`，只解决快照映射、稳定 breaker 名称/版本与指标，不重复实现 Gateway/Resilience4j |
| `flowgate-gateway/metrics` | 复用现有 Micrometer/Actuator，增加有限保护决策指标及节点状态展示；不做自研采样平台 |
| `flowgate-demo` | 最小受控故障 API `GET /demo/fault?mode=ok|500|503|slow`（slow 最多 2000ms）及实际到达计数，默认只在本地演示配置启用；无任意异常字符串/时长/目标 URL 输入 |
| `flowgate-control/static/test-console` | 在现有导航/五步引导上加“故障保护”选项卡：策略表单、受控故障按钮、熔断状态、恢复和指标；同步说明/指南，不删旧操作 |

## 3. 策略参数、作用域及发布规则（Codex 不得自选）

### 3.1 TIMEOUT

- 首版仍沿用 `{"timeoutMs":1000}`，整数毫秒范围 **50..10000**；应用于 SERVICE、API、ROUTE（GLOBAL 过于宽泛，CALLER 不适合 HTTP 客户端路由级共享超时，本块不支持，绑定发布时返回 400）。
- 解析单条 Route 的 SERVICE→API→ROUTE 已启用超时绑定，**取最小 timeoutMs** 作为该 Route 的响应超时上限。TIMEOUT 同一 scope/target 只允许一条启用规则，防止同维度歧义。
- Gateway `RouteDefinition.metadata` 使用官方 `RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR`（毫秒），可写 `CONNECT_TIMEOUT_ATTR` 的**受控本地默认上限 1000ms**（可通过环境变量统一调整，不另设第二种 DB 参数）。需验证 Gateway WebFlux 5.0.4 官方是否在已有全局超时情况下按路由覆盖；未绑定 TIMEOUT 的路由保持当前 Gateway 原行为，不主动禁用默认超时。
- **网关主动超时返回 HTTP 504** 与 `{"code":"UPSTREAM_TIMEOUT","message":"上游响应超时","requestId":"..."}`，在 Gateway 原生 timeout 异常的统一异常处理阶段映射；不拦截已经提交的响应，不能为了包装 JSON 再写第二次响应。不能把真实 Demo 500 映射成 504，也不能靠前端 `fetch` 超时模拟后端 timeout。
- 若 Route 同时绑定 CB，超时是否计为 CB 失败必须有集成测试；相同请求不得被多层计时重复触发两次 fallback。控制面发布验证确保 `timeoutMs` 不大于受控实验上限。

### 3.2 CIRCUIT_BREAKER

保留四项必填字段（整数）：

```json
{"failureRateThreshold":50,"slidingWindowSize":10,"minimumNumberOfCalls":5,"waitDurationMs":3000}
```

校验：`failureRateThreshold` 1..100；`slidingWindowSize` 2..100；`minimumNumberOfCalls` 1..slidingWindowSize；`waitDurationMs` 500..60000。默认 **COUNT_BASED** 窗口，半开允许 **2** 次试探（部署固定常量，不添加新参数），自动 HALF_OPEN 转换。只支持 ROUTE 作用域，避免服务级策略/多路由映射复用误义；已有其他 scope 的旧草稿可以保留，但启用绑定发布时返回 400。

新增一个**可选且受控**字段 `fallbackMode`（枚举 `NONE` / `STATIC_JSON`，默认 `NONE`）；旧四字段草稿继续有效。`STATIC_JSON` **只允许匹配 GET 且明确只读的路由**；不为 POST/PUT/DELETE 开启 fallback、不自动重试、不提供用户输入的任意 JSON/脚本/外部降级地址。

**固定行为**：
1. CLOSED：请求按现有身份→限流→在途许可后进入断路器；下游 HTTP 500/502/503/504 **计为熔断失败**；业务正常 4xx（包括上游 400/404）不计失败，内部认证 401/403、限流 429、并发拒绝 503 **不计入断路器样本**。
2. 窗口样本量达到 `minimumNumberOfCalls` 且失败率达到阈值，进入 OPEN；此后调用**不进入下游**，返回 HTTP 503 `CIRCUIT_OPEN`。区分真实上游 503 与网关熔断短路。
3. `waitDurationMs` 后自动 HALF_OPEN，只放 2 次试探；两次成功回 CLOSED，任一次失败回 OPEN。短路及状态转换用独立指标/时间戳说明；断路器状态以当前应用版本所对应的实例为准，不能在测试台凭 HTTP 响应猜状态。
4. `fallbackMode=STATIC_JSON` 时，**仅 GET** 的熔断/受控失败可以返回 HTTP 503，JSON 至少有 `code:"DEGRADED", degraded:true, source:"GATEWAY_FALLBACK", requestId`；这是明确不可用的静态降级结果，**不返回虚构的业务成功数据或 HTTP 200**。不启用时保留原上游 5xx 或明确网关 503/504。
5. 固定路由 breaker 名：`fg-cb-<policyId>-<configRevision>-<routeId>`，revision 为参数+fallbackMode 的稳定 SHA 摘要，不直接用 `configVersion`（无关发布不应重置熔断窗口）。同版 Route metadata 保存对象名称；参数更新后创建新 breaker，旧在途仍由旧对象完成；不能用动态原始路径、调用方 ID 或 Token 命名。
6. 集成 Gateway 官方 `CircuitBreaker` GatewayFilter Factory 和 Reactor Resilience4j；通过受控参数构建对应实例。**动态配置需要在 Route 刷新前创建/注册**；不允许在 Netty 每请求临时构造配置/新断路器。若该具体框架版本工厂缓存与按版本动态命名行为不相容，必须用小测试证明并作薄适配，不退回到自写熔断状态机。
7. `CircuitBreaker` 与 `SetPath` 的顺序必须使用真实链路测试确认：CB 包裹实际下游调用、无需重复变更请求 Path；`fallbackUri` 只能是内部固定 `forward:/__flowgate/fallback`，不允许用户配置任意 URL，内部处理器对直接访问一律拒绝且不可被已发布公开路径绕过鉴权。只读降级在异常和状态码触发时的行为必须在 Gateway 5.0.4 测试确认，不能只依赖文档示例猜测。

> 官方 WebFlux CircuitBreaker Filter 支持 `statusCodes` 与 `forward:` fallback；具体 Java 配置与版本行为在本块 `mvn` / 真实 HTTP 验证。对无降级规则的 OPEN 503 必须保持可信可辨的错误 JSON；不能将所有 500、超时和 Circuit OPEN 无差别当作业务成功。

## 4. 配置快照、回滚、节点真实状态

1. 沿用 `schemaVersion=1`、`SnapshotCodec.checksum` 和不可变版本，不更改旧 `snapshot_json`。Block3 新发布 TIMEOUT / CIRCUIT_BREAKER 为 `ENFORCED_BLOCK3`，新发布 RATE_LIMIT / CONCURRENCY 仍为 `ENFORCED_BLOCK2`，禁用策略仅保存不执行，所有绑定继续按 Block2 `targetId/targetRouteId` 规则解析。
2. `PolicyRules.validate` 在发布和 Gateway stage 两端严验：参数范围、受控类型、作用域、有效绑定、路由方法的 fallback 限制、单 ROUTE 最多一个启用 CB、TIMEOUT 维度不重复、Route 同版关系。不允许在 Gateway 热路径默默降级参数值。
3. **历史回滚语义**：回滚到旧 Block1/2 PENDING 版本时，TIMEOUT/CB 仍不执行；回滚到 Block3 已生效历史版，生成更大的新版本并恢复对应**原参数**，但这是一个新的配置应用，CB 运行时状态要明确：**新旧同 revision 的 breaker 应尽量复用已有状态；若进程重启或旧对象已淘汰，状态重置为 CLOSED 并在 UI 标记“运行状态可能已重置”，不虚构热迁移**。
4. Control 故障：已应用快照继续服务，并保留原来权限、限流、超时、CB 决策；无法获取新版本不误报节点已更新。Control 恢复后 5 秒周期补拉并上报。Gateway 无可信快照启动仍为 fail-closed，禁止块 0 静态宽泛代理复活。
5. Redis 故障：Caller 鉴权 503；绑定 RATE_LIMIT 的路由 503，不允许由于 CB fallback 将其变成 200 或绕开鉴权；无速率限制的公开 API 可继续按规则运行。Control/MySQL 不应被 Gateway 每请求读取。
6. 节点上报只在**实际缓存路由和元数据已经确认生效**后才能报告；节点的 `lastSeenAt` 不能当“实时在线”，Control 的 `GET /admin/nodes` 追加派生 `freshness`（例如 now-lastSeen<=15s 为 ONLINE，其余 STALE）时必须由服务端计算，不改写历史版本数据；可选择现有节点响应增加 `status` 字段而不新增数据表。
7. Block2 前置竞态修复后，增加 Block3 的“策略/超时/CB 路由元数据与实际缓存同版”定向测试；不存在一条仍承载旧权限却加载新 CB 的请求。

## 5. 真实错误码与观察口径

| 场景 | HTTP | JSON code | 是否进入下游 |
|---|---:|---|---|
| 未认证或 Scope 不足 | 401 / 403 | 原 Block1 语义 | 否 |
| Redis 速率预算耗尽 | 429 | RATE_LIMITED | 否 |
| 本机并发池已满 | 503 | CONCURRENCY_LIMITED | 否 |
| Redis 限速服务故障 | 503 | ADMISSION_UNAVAILABLE | 否 |
| 网关主动等候响应超时 | 504 | UPSTREAM_TIMEOUT | 已尝试转发 |
| CB 处于 OPEN | 503 | CIRCUIT_OPEN | 否 |
| 启用 GET 静态降级 | 503 | DEGRADED，degraded=true | 按触发原因，OPEN 时否 |
| 下游真实业务 500/503（无 fallback） | 500/503 | 下游原状态/可辨来源 | 是 |

**请求 ID** 继续由入口生成并覆盖客户端伪造。错误响应不含密码、Caller ID、Token、内部密钥或 SQL。响应已提交后不能重写状态码。指标统计不能把一条请求同时计为“限流拒绝”和“上游超时”。

指标（最多增加一个小类，优先复用已有）：
- `spring.cloud.gateway.requests`：框架原有 routeId/status/outcome/time；禁用动态 Path 标签。
- `flowgate.admission.requests`：原有限速/并发行为，不改其含义。
- `flowgate.protection.events`（Counter）：标签仅 `routeId, kind=TIMEOUT|CIRCUIT|FALLBACK, outcome=... `，区分 upstream_5xx、open_rejected、half_open_probe、degraded、timeout，基数有限。
- `flowgate.protection.duration`（Timer，可选）：保护阶段实际时长；禁止原始 IP、Caller/Token、Query、requestId 等高基数标签。
- CB 当前状态读取从 Resilience4j 的已注册实例获取，只能管理员通过已有 `/admin/**` 权限渠道访问；Gateway Actuator 继续仅 loopback，**不要让浏览器调用内部密钥接口**。若 Control 需要显示各节点 CB 状态，优先复用现有节点 applied 上报扩展受限摘要（如 `circuitStates:[{routeId,state,revision}]`）并加输入尺寸上限；不可用则标“未知”，不得创造假状态，勿为了可视化给每请求加 Control HTTP 查询。

## 6. 前端与使用指南（本块同等重要）

现有 `/test-console/` 已是原生导航和五步向导，**保留 Block0/1/2 全部真实功能**。当前测试台 UX 仍未经过负责人真实浏览器验收，本块修 R1 目标误导并继续按 `docs/standards/test-console-ux-v2.md` 的可用性标准。

新增“故障保护与恢复”操作区：
1. 用户选中已发布的 Demo 受控路由，表单提供 TIMEOUT `timeoutMs`、CIRCUIT_BREAKER 四项阈值、可选 GET-only `fallbackMode`；参数中文解释、上下限、禁用原因明确，默认收起高级 JSON。
2. “保存草稿→读取当前版本→确认发布→查看节点 appliedVersion”的路径继续直观。只有已发布且网关确认生效时，页面才显示“生效”；不把历史 PENDING 或新版本仅 PERSISTED 显示为 ACTIVE。
3. 提供固定受控故障试验：正常200、上游500、上游503、慢请求触发504、连续失败打开断路器、等待→半开探测→恢复关闭、GET 降级；每个按钮展示预期/实际状态码、耗时、Request ID、是否降级、状态是否由实际数据确认。
4. 在保护状态/指标区展示当前路由、配置 revision、CB CLOSED/OPEN/HALF_OPEN（仅来自实际可查询状态）、`PERSISTED/applied` 和节点在线/过期；缺少数据时写“未取得”，不能硬编码变化动画。
5. 增加“常见故障说明”：401/403/429/503/504 的区别；为什么短路未访问 Demo；超时不等于熔断；为何一条请求可能先消耗限流预算再被短路；保留最近十条无敏感摘要。
6. 同步更新 `docs/guides/test-console-guide.md` 并新增/完善 `docs/guides/block3-fault-testing.md`，给出从启动→草稿→版本发布→故障触发→恢复→回滚的中文点击步骤/真实预期。
7. 不引入 React/Vue/npm、外部 CDN、Grafana。页面通过真实 API 返回表现，不能为了图表直接启用可写 actuator 或泄露 `FLOWGATE_INTERNAL_KEY`。

## 7. 测试环境与最小故障注入

沿用 `scripts/run-local.py`、Docker MySQL/Redis 及三 JVM。增量脚本 `scripts/block3-smoke.py`（Python 标准库），必要时 `scripts/block3-console-test.js`（Node 内置能力），不得覆盖现有测试数据或使用全局 docker prune。新增故障演示只绑定 loopback，仅使用允许的枚举值和最大 2000ms 延迟，不能造成任意长等待或大内存分配。

**示例端到端步骤**（不是已测试结果）：
1. 准备安全隔离的数据库/Redis，启动 Demo、Control、Gateway，登录后台，保留三个既有 Demo 路由；
2. 注册 `GET /api/demo/fault` → `/demo/fault`，绑定 ROUTE CB(50%,10次窗口,5最小,3000ms等待)；另在 slow ROUTE 绑定 TIMEOUT=200ms；
3. 发布 Vn，等节点实际 `appliedVersion=Vn`，正常 fast 200/echo 201、Caller 私有认证不回归；
4. 多次请求故障 GET 模式 500，达阈值后应见 OPEN 503，Demo 实际到达计数不再增长；
5. 超时 slow `delayMs=500` 应见 Gateway 主动 504（不能只读浏览器 fetch 超时），许可归还；
6. 等待 3000ms 后试探，故障解除为 ok，半开有限试探后 CLOSED；GET-only fallback 启用时与 503 `degraded=true` 区分；POST 不降级；
7. 断开 Control 后旧快照/路由继续；Redis 断开时私有或限速 fail-closed，公开无速率的测试接口正常；重连追平版本；
8. 回滚到旧 PENDING 历史版本验证不会意外启用 CB；恢复新版本后继续正常路由。

每个测试应保留实际 HTTP 状态、响应 `code/requestId`、节点版本、Demo 到达计数、Metrics 查询记录，不采集明文凭证。

## 8. 完整定向测试矩阵（不得只测 happy path）

| 范围 | 必须覆盖 |
|---|---|
| Control 验证 | TIMEOUT/CB 参数上界、scope、重复规则、非法 GET fallback 配置、更新草稿/发布回滚，409 CAS 不回归 |
| 历史协议 | Block1/2 PENDING 仍不执行；新 Block3 ENFORCED 执行；checksum/schema 兼容；旧版在途请求持旧保护元数据 |
| Gateway timeout | 超时 504；未绑定无异常改动；下游真实500保留；重复 response commit 受控，许可确实归还 |
| CircuitBreaker | CLOSED 样本窗口、OPEN 无下游访问、HALF_OPEN 两次探测、重新 CLOSED/OPEN、非5xx失败不计数 |
| 降级 | 仅 GET；HTTP503 + degraded=true；不伪造200，不将认证/限速/Redis故障降级；直接访问内部 fallback 拒绝 |
| 版本与缓存 | 同版路由认证+限流+CB；更新参数换 revision，不相关发布保持 state；取消/迟到刷新后缓存与 applied 一致 |
| 故障恢复 | Control 停止继续已应用规则，重启 Gateway 无可信规则时 fail-closed，恢复追平并上报；Redis故障不绕过 |
| 计数指标 | 只统计真实原因；不含动态用户/路径/Token；Actuator 本地只读并保留原 Block2 指标 |
| 前端 JS | 帮助/引导/策略表单/错误码/就地结果/脱敏、历史10条、已有策略目标误导修复 |
| 最小实链路 | 三 JVM+MySQL+Redis 的受控故障、超时、熔断、恢复、发布回滚，记录命令及实际结果 |

只执行新增/修改/直接受影响模块的 Maven/Node/HTTP 定向测试；关键共享契约至少测 Control/Gateway 的直接使用点。正式全量回归、完整三种负载压测和长期高压故障演练留到最后阶段。任何没有运行的项目必须记“未执行”。

## 9. Codex 分步编码顺序（按序执行，无需重新设计）

1. **B3-00**：检查分支/工作区与现有 `.env`，完成第0节 Block2 两项审查收口；定向测试通过并提交 `fix(block2):...`，若阻断则停止。
2. **B3-01**：Control + Contract 改参数校验与发布状态，支持 GET-only 受控 fallbackMode，验证旧版 PENDING 与新 ENFORCED；只运行合适模块的定向测试。
3. **B3-02**：Gateway 超时 Route metadata 与 504 错误语义，验证实际 Gateway 5.x 行为及现有 filter/许可释放顺序。
4. **B3-03**：引入唯一必要 Reactor Resilience4j Starter；实现可配置官方 CircuitBreaker Filter、稳定 revision、受控 fallback 的安全处理与低基数状态指标；验证 OPEN/HALF_OPEN、旧版请求。
5. **B3-04**：受控 Demo 故障场景、Control/Gateway/Redis 配置恢复、节点状态，做一组最小可靠性实链路联调。
6. **B3-05**：原生测试台新增保护页签/说明和功能按钮，同步使用指南；保留 Block0/1/2。执行 JS 契约检查，**负责人真实浏览器验收不冒充自动完成**。
7. **B3-06**：完成第8节增量测试，更新阶段进度表、README、前端能力、Git SHA 和阻塞报告；单独 Conventional Commits 推本块分支，**不要改/合并 main 或进入最终验收**。

复杂 Java/Reactor/Resilience4j/JS 方法和故障恢复流程中文注释，长代码块按 `// 1. ...`、`// 2. ...`、`// 3. ...`，Lua 用 `-- 1.`。尽量三四个真正有职责的类，不造复杂 Adapter/Facade/Factory 套娃；不引入多余微服务和新数据库表。

## 10. 安全与 Git 规范

- 本地先 `git status -sb`、`git fetch origin`，检出远端已存在的 `feat/block3-resilience-observability`；如本地同名分支已存在先核对 SHAs，严禁覆盖未提交内容、`reset --hard`、`clean -fd`、强推。
- 不提交 .env、真实密码、Token、内部密钥、临时容器数据、日志、构建产物；不用大规模测试清理用户现有 Docker 资源。
- Conventional Commits：`fix(block2):...`、`feat(block3):...`、`test(block3):...`、`docs(block3):...`，按实际完工分点提交。
- 这不是可公网部署的生产管理后台；所有 Demo 故障接口/Actuator 控制均在 loopback 或显式测试配置，不可成为任意公网地址代理或绕过登录。
- 遇具体 Spring Cloud Gateway 5.x API 与设计不一致，以**真实依赖源码/编译结果**选最小官方支持实现并回填理由，禁止做看似成功但未生效的 mock 路由或前端固定响应。

## 11. Codex 进度回填（2026-11-06 实际执行）

| 子项 | 当前真实状态 |
|---|---|
| 分支与代码提交 | feat/block3-resilience-observability；前置fe0f391，保护实现4fc7562，业务前端519cb46、表单重读修正c79a198；脚本证据和文档另作交付提交 |
| Block2 R1/R2 | 已修复并定向验证：已有目标锁定，取消/迟到恢复持有互斥权直到真实缓存恢复结束；Sinks控制交错，不用sleep遮盖 |
| Contract/Control | TIMEOUT/CB边界与作用域、GET-only可选fallback、新ENFORCED_BLOCK3、旧PENDING兼容、CAS/历史checksum保持 |
| 官方超时 | Netty路由response-timeout与connect-timeout；504 JSON、可信Request ID、原许可释放；实测路由覆盖已有全局超时 |
| 官方熔断 | BOM Starter+Gateway CircuitBreaker Filter；精确版本注册、稳定revision、真实CLOSED/OPEN/HALF_OPEN、2次成功恢复及任一次半开失败回OPEN |
| 受控降级 | 固定内部forward，仅GET返回503 DEGRADED；直接访问404、POST配置发布400，认证/准入失败不降级 |
| 故障恢复 | Control离线保留保护、Gateway无快照首启fail-closed、Redis暂停/恢复、重新追平版本均通过实链路 |
| 指标与节点 | flowgate.protection.events仅routeId/kind/outcome；真实CB状态经受限节点上报，ONLINE/STALE由服务端派生，未取得运行状态标未知 |
| Demo故障接口 | 显式开关，默认404；固定ok/500/503/slow<=2000ms，真实到达计数，普通接口不变 |
| 业务前端 | 默认业务概览；服务/API关联下拉与直接保存草稿、流量策略、保护表单和发布/节点操作；JSON/首次使用/故障抽查折叠，原按钮保留 |
| 自动化 | 全部受影响模块34个JUnit通过；后续Gateway/Contract29个定向复测通过；Node交互和最终隔离HTTP链路通过 |
| 人工与最终验收 | 浏览器操作由负责人进行，未执行；未做最终全量回归/正式压测/公网部署审查，不进入后续阶段 |

### 11.1 实际文件与必要依赖

- Contract `PolicyRules`、Control `CatalogService/ReleaseService`：共用范围校验、已有表/API和schemaVersion=1，无新增管理表或业务模块。
- Gateway `ProtectionPolicyResolver`与`CircuitBreakerConfigRegistry`：同版冻结、注册先于Filter构建、稳定policyId+revision+routeId名称。`SnapshotPollingService`实际缓存与保护metadata核对后才确认；节点仅报告applied快照所引用实例。
- `ProtectionResponses`：官方异常JSON边界及固定内部fallback；原生5xx状态、主动504与断路器503严格区分，不重写已提交响应。`RequestIdWebFilter`保存可信exchange属性，避免异常处理拿到原请求时丢失Trace ID。
- `ReleaseController`：受限运行摘要仅内存保存、最多100节点/每报告100条状态，摘要字段/路由/状态/revision严验；缺少上报/Control重启后显示未知。节点时间写入前截断秒，避免TIMESTAMP舍入产生短暂未来时间。
- Demo `DemoFaultController`：本机显式实验开关，不增加业务进程/任意延迟或目标URL。`.env.example`只增加可选false开关，**没有修改用户.env**。
- 唯一新增直接运行依赖：`spring-cloud-starter-circuitbreaker-reactor-resilience4j`，由现有Cloud BOM解析；实测Spring Cloud CircuitBreaker Resilience4j5.0.3、Resilience4j2.3.0、Gateway5.0.4、Java17.0.18/Boot4.0.9。该依赖是官方CB Filter所需，没有自行实现状态机或新增Kafka/Redisson/重型前端框架。
- Plus代码本块没有新复制；只继续使用块2已有Lua。熔断/超时均复用官方能力，既有第三方归属文件保持。

### 11.2 精确版本适配与业务边界

1. Resilience4j2.3.0把半开最小样本量与CLOSED的minimumNumberOfCalls耦合。为严格实现“半开两次成功才关闭、任一次失败回OPEN”，固定半开许可2，框架最小样本至少2；通过官方onError事件/transitionToOpenState补齐半开首失败，以及用户min=1首样本失败的等价行为。不维护自定义运行状态或计数器；真实注册表单测与HTTP状态转换均已执行。
2. 关闭Starter默认TimeLimiter和隐式Bulkhead，避免默认1秒计时改变官方Netty超时语义。隔离测试显式设置全局1500ms：未绑定fault slow在1500ms超时；route slow200ms更早超时；route fault2500ms+CB可等待2000ms并200，证明两方向覆盖且CB没有额外1秒计时。
3. 无fallback时，官方statusCodes Filter先把上游500/502/503/504计入CB再抛异常；网关保留原HTTP状态并返回`UPSTREAM_ERROR/source=DOWNSTREAM`，**不原样保留该错误Body**。正常业务响应仍透传；OPEN由CIRCUIT_OPEN区分。此处采用任务书允许的“原状态/可辨来源”，不伪装成功。
4. 前端按负责人新澄清调整为简单业务页面，而非继续扩展复杂验收平台；五步帮助和原始协议细节折叠保留。普通表单使用原管理API；SERVICE/API范围TIMEOUT仍可用原高级入口，不虚构GUI覆盖全部复杂配置。
5. 旧PENDING历史保持不可变；脚本仅在**自有临时库**追加旧协议夹具，以Python规范JSON/SHA生成受校验载荷，未改写已有历史。实测旧TIMEOUT50ms不执行、旧CB连续500不熔断，再发布B3、回滚旧PENDING并恢复B3历史参数。checksum原值保持。

### 11.3 实际命令与结果

```bash
source .env
mvn -B -ntp -pl flowgate-gateway -am test                        # 前置：Contract5/Gateway17通过
mvn -B -ntp -pl flowgate-gateway -am package -DskipTests         # 首次解析唯一新增Starter，不计作测试通过
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package
mvn -B -ntp -pl flowgate-gateway -am test package                # 追加守卫后Contract7/Gateway22通过
mvn -B -ntp -pl flowgate-control -am package -DskipTests         # 更新业务静态资源，不重复宣称测试已运行
node --check flowgate-control/src/main/resources/static/test-console/app.js
node scripts/block2-console-test.js
node scripts/block3-console-test.js
PYTHONPYCACHEPREFIX=/tmp/flowgate-block3-pycache python3 -m py_compile scripts/block3-smoke.py
python3 scripts/block3-smoke.py
git diff --check
```

- 全部直接受影响模块JUnit：**34通过、0失败/跳过**（Contract7、Control1、Gateway22、Demo4）。包含范围/GET降级/旧PENDING、官方注册表min=1及半开、原始exchange错误Trace、默认禁用故障API、缓存取消/迟到/元数据不一致与恢复、新旧许可等；之后Gateway/Contract29个定向复测通过。没有宣布全项目最终回归通过。
- Node：原有导航、登录清密、角色按钮、草稿/版本、409不重试、429/400与摘要脱敏，及新保护表单/实际目标路径/504与降级渲染/节点真实状态/目标锁定/业务关联下拉及直接保存均通过。这是模拟DOM契约，不是浏览器人工验收。
- 最终独立实链路：MySQL/Redis临时容器、Demo/Control/两个Gateway、随机端口；**192条无凭证HTTP记录**见`docs/progress/evidence/block3-smoke.json`，`passed=true`。状态统计：200×122、201×30、500×7、504×6、400×5、503×11、404×3、401×4、403×3、429×1；错误码均为有意测试结果，不是把所有非200当失败。
- 实测包括OPEN时Demo到达计数不增、两次半开成功/一次失败恢复、并发503不计CB失败、401/403/429/Redis503不降级、可写Gateway Actuator未开放、节点状态受管理员权限保护、保护指标只有受控低基数标签、Control/Gateway/Redis故障恢复与历史双向回滚。
- JVM与容器由脚本finally清理自己的资源，未停止用户IDEA应用或本地持久化容器。没有执行电脑界面控制、全局Docker prune或改写用户数据库。

### 11.4 失败记录、注释与已知限制

- 初次真实超时504的Body requestId为空：外层异常处理得到原始exchange请求，已改用入口生成的可信exchange属性，并加单测及HTTP Header/Body一致性断言，复测通过。
- 节点刚上报却短暂STALE：MySQL TIMESTAMP秒精度舍入可能大于当前瞬间；写入前withNano(0)修正，节点单测/实链路通过。
- 并发拒绝增量测试初次用200ms窗口，观测轮询时首请求已超时，后续200其实符合正确行为。改为受控1000ms/2000ms，明确断言首Future未结束再发送拒绝请求；没有修改生产限流逻辑掩盖结果，最终通过。
- Shell默认java21；所有Maven使用.env指定的JAVA_HOME17.0.18，脚本使用该JAVA_HOME/bin/java，不把shell默认版本写成实际测试JDK。
- 中文注释已检查：关键Java/Reactor/JS路径有中文说明，长逻辑编号；Gateway源码无JDBC/Servlet/同步StringRedisTemplate/ThreadLocal/阻塞sleep。秘密值检查未发现本机密码、内部Key或BCrypt进入变更；git diff --check通过。
- 运行状态由最近上报提供，Control内存摘要不持久化；Gateway重启会重建CB，页面说明可能重置，不能承诺状态热迁移。稳定revision进程内复用，无关发布不重置窗口。
- 官方注册表为复用历史revision保留实例，频繁参数发布可能增加进程内实例数量；本机演示尚未做长期容量/清理验证。Gateway取消/超时释放本机许可不代表Servlet下游已停止睡眠工作，不宣称强制中断下游。
- 负责人浏览器/窄屏/键盘体验、正式长时间故障演练、生产部署、最终完整回归和正式性能测试**未执行**。Block1/2/3不因本轮自动合并或宣称全部验收完成。

**交付结论：功能块3编码与上述定向验证已完成，业务前端和指南已同步，等待ChatGPT与项目负责人审核；不合并main、不开始最终验收。**
