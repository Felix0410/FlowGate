# 功能块 2｜分布式限速、在途并发保护、热点隔离（实施任务 V2.0）

> 状态：**设计已准备，尚未授权编码；功能块 1 审核及必要修复完成后再开始**。
> 预备设计分支：`docs/block2-traffic-design`（仅文档）；未来实现分支建议 `feat/block2-traffic-governance`，须从确认后的 Block1 稳定提交创建。
> 执行范围：B06/B07/B08 + Block1 已有控制台的易用性修复与使用指南。不提前做 Block3 熔断、动态负载自适应或正式性能压测。
> 统一阅读：`AGENTS.md` → 本文 → `docs/standards/test-console-ux-v2.md` → `docs/guides/test-console-guide.md`；背景见 `docs/progress/block-1-control.md`。

## 1. 前置核对、禁止跨阶段

1. Block1 远端 PR #1 当前仍为 Draft；代码已有实质交付，不能把进度表中旧的“403 未推送”当现状。完成 Block1 的人工最低验收与审查意见处理后，负责人明确授权再切换到 Block2 编码。
2. 进入本块前复测 `SnapshotPollingService` 在缓存刷新超时、迟到结果、失败/并发刷新时，旧已应用路由是否真正保留；如果实际缓存可能与 store.applied 分离，先在 Block1 修复，**不能把该风险带入限流版本切换**。
3. 核对当前生效的 `fg_policy` 字段：`type, scope, parameters_json, enabled`；`fg_policy_binding` 字段：`policy_id, target_type, target_id`。Block1 已有 RATE_LIMIT 参数 **`capacity` 与 `refillPerSecond`**、CONCURRENCY **`maxConcurrent`**，本块沿用，不另发明 `ratePerSecond/burstCapacity/maxInFlight` 同义字段。
4. 当前快照是 `Snapshot(schemaVersion=1, services, apis, routes, policies, bindings)`；`RouteSpec` 含 routeId/method/publicPath/upstreamPath/upstreamUri/isPublic/requiredScope/priority/configVersion。Block1 把策略标为 `PENDING_BLOCK_2_3`，真实执行目前不存在。
5. 保持 Java17 / Boot4.0.9 / Cloud2025.1.4、MVC Control、Gateway WebFlux、Reactive Redis、JDK 并发原语、Micrometer。**无新增 Maven 模块、无 Sentinel/Redisson/Kafka/Nacos、无 Servlet/JDBC/同步 Redis 进入 Gateway**。

## 2. 完整可演示业务闭环

管理员从易用的测试台选择一个 Demo 路由与策略 → 保存草稿 → 发布不可变新版本 → 网关校验并真正应用该版本 → 进行限速或在途并发请求 → 命中策略时返回可解释的 429 或 503，并且下游**未被调用** → 查看本次规则、版本、拒绝数 → 修改/回滚策略，旧在途请求许可安全释放 → 在小规模共享资源竞争中观察慢路由受控后 fast 业务是否改善。

不做：熔断、自动降级、自适应限流、分布式并发租约、多 Key 跨维度原子性、完整生产管理 UI、长期压测、大规模集群测试。Block1 的管理员鉴权、Caller Scope、公共请求、发布与回滚必须继续正常工作。

## 3. 固定依赖与最少业务类

| 模块 | 新增/修改点 | 职责 |
|---|---|---|
| `flowgate-contract` | 扩展受控快照 `policies/bindings` 字段校验，不改已有顶层结构 | 对策略生效状态、版本与关系进行确定性校验 |
| `flowgate-control` | `CatalogService`、`ReleaseService` 的参数/绑定/发布校验 | 从数据库 ID 导出快照可用的策略绑定，不让 Gateway 访问 MySQL |
| `flowgate-gateway` | `policy/EffectivePolicyResolver` | 在 RouteDefinition 构建阶段，把当前发布版本对应的策略集合冻结进 **Route metadata** |
| `flowgate-gateway` | `admission/AdmissionGlobalFilter` | 排在 CallerAuthFilter（当前 order=-100）之后、Netty 路由转发之前执行治理 |
| `flowgate-gateway` | `admission/ReactiveTokenBucketLimiter` + `resources/lua/token-bucket.lua` | 单 Redis Key、单 Lua 原子令牌桶，返回准入/拒绝/故障 |
| `flowgate-gateway` | `admission/LocalConcurrencyLimiter` | 原生非阻塞 tryAcquire；许可随请求终止准确释放 |
| `flowgate-gateway` | `admission/AdmissionMetrics` | 最小指标：接受/拒绝/故障、策略类型、路由、限流延迟 |
| `flowgate-control/static/test-console` | 原有三文件按需拆小；不得新增 npm | 先修复现有测试台操作体验，再补限流/并发实验区 |

`flowgate-demo` 只允许加**可配置且有界**的竞争实验参数，不增加点评/订单/复杂下游服务。若现有三个 API 已足以复现慢请求挤占，则不改 Demo。

## 4. 固定发布契约：兼容现有 Block1

### 4.1 保留原有 REST API

仍用 `GET/POST/PUT /admin/policies`、`POST /admin/policies/{id}/bindings`、`POST /admin/releases/publish` 和原回滚/节点接口。**不新造并行管理接口，也不变更现有表结构**。前端使用可读表单生成同样的 JSON：

```json
{
  "code": "slow-route-rate",
  "type": "RATE_LIMIT",
  "scope": "ROUTE",
  "parametersJson": "{\"capacity\":5,\"refillPerSecond\":2}",
  "enabled": true
}
```

CONCURRENCY 参数为 `{"maxConcurrent":2}`。本阶段 RATE_LIMIT 的 capacity 与 refillPerSecond 均为正整数，上限沿用现有受控校验；请求成本固定 1。

### 4.2 快照绑定与旧版本兼容（关键）

- `fg_policy_binding.target_id` 目前是数据库 BIGINT。GLOBAL=0、SERVICE=fg_service.id、API=fg_api.id、ROUTE=fg_route.id、CALLER=fg_principal.id。
- 当前发布 `routes[]` 只含 routeId，没有 fg_route.id；**不能在 Gateway 错把 routeId 字符串与数据库数字匹配**。本块在 Control 生成快照时，对 `targetType=ROUTE` 的 `bindings[]` **额外导出 `targetRouteId`（稳定 route_id）**，同时保留 targetId 供审计。其他作用域维持现有字段。RouteDefinition 构建阶段结合：匹配路由的 `method+publicPath` 找到 `apis[].id/serviceId`，再应用 GLOBAL / SERVICE / API / ROUTE 绑定；CALLER 根据认证后主体 ID 判定。
- 新发布 `policies[]` 的 RATE_LIMIT、CONCURRENCY 增加 `executionStatus:"ENFORCED_BLOCK2"`；TIMEOUT/CIRCUIT_BREAKER 仍为 `PENDING_BLOCK3`。旧的 `PENDING_BLOCK_2_3` **绝不被 Gateway 偷偷当成已经启用的治理规则**。已存在的旧快照保持不可变、checksum 不重写；新发布/回滚产生更大 version。
- 保持现有 `schemaVersion=1` 的顶层结构与字段，因为上述只是现有 Map 的受控扩展；checksum 在完整载荷上按 `SnapshotCodec` 重新计算。Block1 历史快照无 `targetRouteId` 时不执行未曾生效的待办策略。回滚到旧版时，前端明确显示“策略处于待执行/未治理”，不能伪称 ACTIVE。
- 必须做兼容测试：读历史 V1、发新版本、回滚历史版本、SHA 完整性、不同版本在途请求不串用新规则。如上述 Map 扩展无法满足必要安全校验，先报告原因，由负责人批准才升级 schemaVersion，不能悄悄破坏历史回滚。

### 4.3 唯一规则和业务范围

- 一条启用策略只能绑定符合自身 scope 的目标，发布时再检查关系有效且目标启用。对于相同的 `(type,scope,targetType,targetId)`，**只允许一条启用策略**；冲突发布返回 400，避免暗中改变优先级。
- RATE_LIMIT：GLOBAL/SERVICE/API/ROUTE/CALLER 可用；优先级固定 **GLOBAL → SERVICE → API → ROUTE → CALLER**。
- CONCURRENCY：首版只支持 SERVICE/ROUTE，其它 scope 的已保存草稿可以存在，但**绑定后发布必须拒绝**，不能静默忽略。
- CALLER 配额只对已认证的私有路由主体生效；公共匿名路由不虚构 Caller ID，使用 GLOBAL/SERVICE/API/ROUTE 配额。
- 如果绑定未启用的策略，该绑定不执行，但保留草稿；发布不可包含悬空引用、非法参数或把控制面地址当上游。不要因为增加流控而松开 Block1 安全验证。
- 快照切换期间必须从当前已匹配路由的 **同版本 Route metadata** 取得策略列表，而不是每请求读取可能更新的全局 applied/candidate。

## 5. Reactive Redis 令牌桶：执行算法不可自选

### 5.1 Key / 参数

`fg:rl:<policyId>:<policyRevision>:<scope>:<scopeId>`

- policyId 来自数据库稳定 ID；policyRevision 使用 **类型+scope+规范化参数 JSON 的 SHA-256**（可取足够长且固定的十六进制摘要，建议 16 字节/32 字符）。无关配置发布不改变该摘要，参数改变则换 Key。拒绝使用 `configVersion` 直接当 revision，因为无关发布会意外清空配额。
- GLOBAL scopeId 恒为 `global`，SERVICE/API/ROUTE 使用稳定目标 ID 或 routeId，CALLER 使用内部 principalId（不含用户名、Token、原始 IP）。不要把 requestId、原始路径或令牌直接放进 Redis Key。
- 单 Key Redis Hash 字段 `tokensMilli`（定点整数，1 token = 1000 单位）和 `lastMs`；脚本以 Redis TIME 取时，首次赋值 capacity×1000。
- 所有数值设上限以避免 Lua double 整数精度溢出；参数 1..1_000_000，固定每次成本 1000 单位。ttl = `min(86400, max(60, 2 * ceil(capacity/refillPerSecond)))` 秒（整数向上取整），确保有界过期。

### 5.2 脚本固定步骤

1. 读取 Redis TIME 毫秒、`tokensMilli`、`lastMs`，对负向时间差按 0 处理。
2. `tokensMilli = min(capacity*1000, previous + elapsedMs*refillPerSecond)`；若足够，则减 1000 并返回允许；否则拒绝并计算建议 `retryAfterMs`。
3. 不论允许/拒绝都写回最后时间和剩余令牌、刷新有界 EXPIRE。Lua 返回固定的**整数数组**：`[allowed(0/1), retryAfterMs, remainingMilli]`。Reactive Redis 执行时明确声明 Spring 的脚本类型/返回类型，按实际框架编译与集成测试，禁止盲目 Long/Integer 强转。

每个 Key 的判断与扣减原子；多维 Key **不做跨维事务**。上层通过、下层拒绝时，上层已扣预算视为本层已接收的尝试，不回滚。早一层拒绝，不再检查后面的层。统一拒绝 429 `RATE_LIMITED`，可得可靠重试秒数时才写 `Retry-After`；不返回虚构 0 秒。

Redis 执行异常、超时等返回 503 `ADMISSION_UNAVAILABLE`，拒绝进入下游；没有绑定限速规则的公开路由无需触碰限流 Redis。

## 6. 本机在途并发许可（与分布式速率明确分离）

- 仅 SERVICE → ROUTE 两层，`tryAcquire` 立即返回、**绝不阻塞 Netty 事件循环**；先服务后路由，路由失败时释放服务许可。
- LocalConcurrencyLimiter 采用线程安全 `AtomicInteger` CAS 或 JDK `Semaphore.tryAcquire`；按 `(policyId,policyRevision,scope,scopeId)` 为配置快照维护状态。快照切换后旧许可对象保留到其请求结束；不得直接清空旧计数/让旧请求释放到新对象。
- 全部许可取得后才调用 Gateway 下游 `chain.filter`；用 Reactor `Mono.defer` + `doFinally` 在 complete/error/cancel 都释放。通过 `AtomicBoolean` 防止双释放；获取中途失败立即释放前面的许可。
- 拒绝时 HTTP 503 `CONCURRENCY_LIMITED`，附 routeId、可读理由和 X-Request-Id；绝不伪造下游 HTTP 200。**本机许可不等于多 Gateway 实例共享的总并发预算**。
- 速率许可先于并发许可，符合“多层独立限速先计数，然后检查当前执行容量”的契约。并发失败不会退还前面已扣的速率令牌，应在使用指南写明。

## 7. 过滤顺序、错误与低基数指标

Gateway 已有 CallerAuthFilter order=-100。新增 AdmissionGlobalFilter 建议 order=-90，必须用测试验证**先认证再流控再实际代理**。认证成功把 `Session.principalId` 保存在当前 exchange attribute，不能放可信身份头、更不能调用 ThreadLocal；只在需 CALLER 预算时使用。public 路由按匿名行为正常代理。

拒绝 JSON 与 Block1 一致：`{code,message,requestId}`，附 `routeId` 和 `policyCode` 等有限信息时不得泄露 Caller ID 或密钥；前端显示真实 429 / 503、哪条规则触发、剩余/建议等待时间（如有）。错误原因区分 `RATE_LIMITED`、`CONCURRENCY_LIMITED`、`ADMISSION_UNAVAILABLE`。401/403 优先，未授权尝试不消耗限流配额。下游 5xx 不被网关误记为限速拒绝。

Micrometer 优先复用现有 Actuator 和 Gateway timer，只新增固定低基数指标：`flowgate.admission.requests` (outcome、policyType、routeId)、`flowgate.admission.duration`；不以原始路径/用户/token/请求 ID 作为 tag。若未来路由数量高，需对 routeId 指标基数设边界，本块先只跟随白名单发布的 Demo 路由。

## 8. 前端体验修复与使用说明是本块强制首项

在开发治理逻辑之前，先按 `docs/standards/test-console-ux-v2.md` 调整现有五区长表单：
- 提供首屏“5 步引导”（环境检查→登录→初始化公共 Demo→发布与确认节点→接口测试），以及查看当前进度、依赖不可用的修复指引；
- 管理目录提供可选模板表单/字段说明，默认隐藏原始 JSON 高级编辑器；发布与回滚需要确认对话框，突出 `PERSISTED` 与 `appliedVersion` 的差异；
- 结果显示操作名称、请求 URL（不含凭证）、HTTP、耗时、X-Request-Id、中文解释，留最近 10 条**无敏感数据**记录，不再全页面共用一个会被覆盖的结果窗；
- 后续新增策略向导：选择路由/维度→RATE_LIMIT 或 CONCURRENCY→输入真实规则参数→保存/发布→执行可控小测试；统一标明 RATE_LIMIT/CONCURRENCY“已生效”和 TIMEOUT/CB“待 Block3”；
- 把 `docs/guides/test-console-guide.md` 的启动/测试步骤在页面“帮助/使用指南”链接显示，**必须有可按图操作的中文说明**；
- 保留全部 Block0/1 已能操作的功能，所有按钮真实调用后端，不新增 Node/Vue/React/npm 或伪数据。前端的实际体验由负责人在最小 Demo 阶段集中检查。

## 9. 热点隔离的最小可验证实验

- 使用现有 GET fast 与 slow（0–2000ms），两类请求应在同一个受限 Demo 资源环境中竞争。只有确实存在可复现资源竞争时，才讨论“隔离效果”；若无竞争，按需在 **Demo 的可控测试配置** 中设有限的共享请求处理容量，不引入新的业务进程。
- 同一机器、同一次启动/配置、相同客户端并发与持续时间，做两组 **短实验**（如 15～30 秒，而非项目大压测）：A 不启用策略，B 对 slow 设置较低的 ROUTE 令牌桶和本机并发上限。
- 分别记录 fast 请求成功数、P95/P99（样本少时注明不稳定）、slow 请求 429/503 数、Demo 在途数、请求总量、CPU/环境条件；保留脚本与**原始结果**，不用预设“提升百分比”。
- 实验失败或没有改善要如实记录，不把“一个路由被拒绝”包装成端到端热点隔离成功。正式多组长压测仍在最终验收执行。

## 10. 本块测试矩阵（全部是 Codex 必做定向验证）

| 测试组 | 必过条件 |
|---|---|
| 兼容/发布 | 原有三 Demo、Caller 401/403/200、发布 Vn、回滚、旧 snapshot checksum 均有效；旧待办规则不被静默激活 |
| 参数与作用域 | 合法/非法 RATE_LIMIT/CONCURRENCY、错误/重复绑定、缺失引用、未支持 scope 发布拒绝 |
| 单 Key Lua | 首次满桶、连续拒绝、补充、时间回拨、TTL、脚本返回整数类型、同 Key 并发原子性 |
| 多维语义 | GLOBAL→SERVICE→API→ROUTE→CALLER、前层扣费+后层拒绝、有配置/无配置；Caller A 不影响 Caller B |
| 认证+准入 | 401/403 优先，公共/私有均遵守各自适用策略，拒绝请求不进入 Demo |
| 并发许可 | 逐层获取、第二层失败释放第一层、下游 5xx、上游取消、请求超时/异常/正常完成都归还且不负数 |
| 动态版本 | 未改策略的无关发布不换 Key；参数变化换 revision；旧在途请求释放旧许可；回滚真实变更策略 |
| Redis 故障 | 已绑定策略 503 且不放行；未绑定的普通公开路由仍可访问；恢复后重新准入 |
| 两网关小测试 | 使用相同 Redis Key 的速率总预算跨实例共享；明确本地并发上限不共享 |
| Web UX | 真实浏览器手动操作入门流程、常见 401/403/409/429/503 可读；Tab/表单不丢已实现功能；不泄露凭证 |
| 短热点实验 | 同样负载的 A/B 两组、原始记录与限制说明，不做长时正式压测 |

只对新增/修改/直接受影响模块运行 Maven 定向测试，至少有一条三 JVM + MySQL/Redis 真实请求链路。增加 `scripts/block2-smoke.py` 和 `scripts/block2-hotspot.py`（或明确等价且简单的脚本）；避免为了测试引入重型性能框架。本块不得覆盖/删除用户本地数据、环境变量和已有容器/未提交改动。

## 11. Git、中文注释与 Codex 执行顺序

1. 在 Block1 修复/审核确认后，**从其稳定提交**创建 `feat/block2-traffic-governance`；若本地已有同名分支，先核对，不要强推、reset/clean 或覆盖用户未提交工作。只在当前功能分支提交；不要改 main。
2. 先做 UX 修复与 guide 可读性，再锁定发布绑定契约和路由元数据，再做 Lua 单维→多维→并发许可→短热点验证；每一步有最小局部定向测试，但**不要求负责人逐个子步骤手动确认**。
3. 关键类/方法与复杂算法全部中文注释，较长 Java/Lua 块按 `// 1. ...` / `-- 1. ...` 等编号。重点说明：早层扣减语义、为什么无跨 Key 原子性、如何安全释放许可、旧版本兼容。
4. 每完成业务块同步维护测试台能力标识、使用指南、README 与本文件第 12 节；遵循 Conventional Commits，记录真实命令/运行结果。功能块 2 完成后停下等审核，不自动开启 Block3。

## 12. Codex 进度回填（目前均未实现）

| 项目 | 真实状态 |
|---|---|
| Block1 复审与负责人授权 | 待完成 |
| 功能块 2 实现分支 / SHA | 未创建 |
| 前端可用性改造 + 使用指南 | 设计已备，前端代码尚未改造 |
| 发布快照绑定字段兼容 | 未编码 / 未测 |
| Redis Lua 令牌桶与多维规则 | 未编码 / 未测 |
| 本机并发许可与释放 | 未编码 / 未测 |
| 认证/治理顺序与 HTTP 错误 | 未编码 / 未测 |
| 两网关 Redis 共享小测试 | 未执行 |
| 热点小实验、原始结果 | 未执行 |
| 负责人/ChatGPT 审核 | 未开始 |

完成时必须回填本块新增文件、版本与必要依赖、Git 分支/SHA、**真实构建和测试命令/结果**、可重现 Demo 的启动与操作方法、已知问题。任何没执行过的测试明确写“未执行”。

**本块通过标准：** 正式发布的限速/本地并发策略在网关真实生效、拒绝不进下游、Redis 速率跨实例共享、请求终止许可不泄漏、旧版快照与认证不回归、短热点对照真实可解释，且前端已能让非作者按照中文使用指南独立完成一次演示。