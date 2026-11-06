# 功能块 2｜分布式限速、在途并发保护、热点隔离（实施任务 V2.0）

> 状态：**2026-11-06负责人明确授权开始；实现、定向测试与短热点对照已交付，浏览器人工可用性及最终审核未完成**。
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

## 12. Codex 进度回填（2026-11-06 实际执行）

| 项目 | 真实状态 |
|---|---|
| 负责人授权与R1前置 | 负责人明确“可以开始了”；块1基线6a3e038修复真实缓存恢复，块2继续补迟到缓存写入恢复测试；最终人工验收不冒充通过 |
| 分支/实现SHA | feat/block2-traffic-governance；前端618aef4，治理实现d89f152；本节及脚本另作交付记录提交 |
| 测试台与指南 | 已实现分区导航、五步引导、模板/策略表单、独立响应卡、最近10条脱敏摘要、发布/回滚确认、真实版本/规则状态；自动化DOM交互契约通过，真实浏览器尚未执行 |
| 快照兼容 | schemaVersion=1保持，ROUTE导出targetRouteId，旧PENDING不执行、旧checksum不改写，规则随同版Route冻结 |
| 令牌桶 | Reactive Redis单Key Lua、固定点整数、Redis TIME、有界TTL、独立revision；五维有序扣减、早层不退费 |
| 本机并发 | SERVICE→ROUTE，非阻塞CAS，部分失败释放，normal/error/timeout/cancel及旧版本许可独立释放测试通过 |
| 认证与拒绝 | 401/403不消耗Caller预算，Caller A限额不影响B；429/503拒绝不调用Demo，Request ID一致 |
| Redis故障 | 专用容器pause/unpause，受保护路由503，未绑定公开fast200，恢复后准入通过 |
| 两Gateway | 相同速率Key共享预算；本机并发各自独立，真实HTTP验证通过 |
| 热点短对照 | 同启动8个Tomcat线程、16个slow客户端/1个fast客户端、同发送节奏，A/B各15秒，原始样本见evidence/block2-hotspot.json |
| 最终审核 | 尚未执行；没有合并main或开始Block3 |

### 12.1 主要代码与技术复用

- `PolicyRules`：两端共享的发布/应用边界参数、关系和唯一规则验证；`ReleaseService/CatalogService`在现有表/API导出绑定与执行标识，current响应增加白名单policyStates，未新增管理表或接口。
- `EffectivePolicyResolver`：按同版API/Route冻结五维策略、规范化参数SHA revision；不在请求中读取candidate/applied或MySQL。
- `AdmissionGlobalFilter/ReactiveTokenBucketLimiter/LocalConcurrencyLimiter/AdmissionMetrics`：真实准入、明确429/503、单机非阻塞许可、低基数指标。无新增Maven依赖或业务模块。
- Lua仅参考Plus令牌数学、Redis TIME和拒绝思路，重写为单Key定点数组返回；未复制Servlet、ThreadLocal、同步Redis、券/IP/User双扣。来源与Apache许可保留于`THIRD_PARTY_NOTICES.md`及`licenses/`。
- Demo仅新增默认关闭的`FLOWGATE_DEMO_OBSERVATION=true`实验观测，用实际下游调用数证明拒绝不转发；共享资源使用已有Tomcat线程配置，没有增加下游业务。观测端点不作为网关发布路由。

### 12.2 实际命令与结果

```bash
# 使用本机已有 .env 中的Java17；秘密不打印、不提交
source .env
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package
node --check flowgate-control/src/main/resources/static/test-console/app.js
node scripts/block2-console-test.js
PYTHONPYCACHEPREFIX=/tmp/flowgate-block2-pycache python3 -m py_compile scripts/block2-smoke.py scripts/block2-hotspot.py
python3 scripts/block2-smoke.py
python3 scripts/block2-hotspot.py
git diff --check
```

- Maven：**24个JUnit通过**（Contract5、Gateway16、Demo3），Control无独立JUnit，本轮通过真实MySQL/Redis HTTP链路验证相关发布路径；未以无测试冒充单测覆盖。
- Node：导航、登录清密、角色按钮、版本读取、真实目标路径、429/400渲染、409不重试及摘要脱敏通过。这是自动化DOM契约测试，**不等价真实浏览器测试**。
- smoke：隔离MySQL/Redis容器、Demo/Control/两个Gateway JVM，真实认证/代理/发布/回滚/拒绝/Redis故障恢复/节点与指标/60秒TTL自然过期通过；在退出finally中清理自有JVM与容器、匿名卷，不停止用户已有IDEA服务和本地Docker依赖。
- hotspot：实际执行两组15秒；A fast成功14，P95/P99=2007.691ms（仅14样本）；B fast成功146，P95=7.13ms、P99=21.729ms，slow成功25、429共1056、503共6。原始逐请求/在途观测与环境限制保留于`docs/progress/evidence/block2-hotspot.json`。同闭环客户端配置，治理后拒绝更快导致尝试总量上升，不声称相同完成吞吐的正式性能比较。
- 初次沙箱Maven失败原因为Mockito无法附加JVM，允许测试JVM附加的环境重跑通过。首次Redis stop/start恢复测试失败：Docker随机宿主端口重启后改变；改用pause/unpause保持端口进行故障恢复，重跑通过。保留此失败事实，不归咎于已证明恢复的准入算法。
- 中文注释检查：核心治理类、发布/版本边界、Lua与复杂方法均有中文说明及编号；Gateway源码扫描无Servlet/JDBC/同步StringRedisTemplate/RequestContextHolder/ThreadLocal。`git diff --check`通过；本机密码/内部Key/BCrypt值扫描未进入变更。

### 12.3 未执行项、使用方法与风险

1. **真实浏览器五步操作、1280px/窄屏布局、键盘交互、VIEWER/OPERATOR实际页面易用性及负责人人工验收未执行**：遵循负责人此前不使用电脑控制的要求；不能据自动DOM测试宣布完整Web UX验收通过。真实HTTP角色/凭证行为由既有块1和本轮联调覆盖，页面布局仍待人工。
2. 未做全项目统一回归、长时间/正式大压测、Redis Cluster、生产安全及公网部署验证。P99样本很少，CPU仅ps生命周期平均值，开发机其他负载未隔离。
3. 层次令牌扣减不是跨Key原子事务；并发失败不退已扣令牌；本机并发不是跨节点总容量。Redis故障对绑定限速路由fail-closed。
4. 现有数据和历史版本保留；旧PENDING版本回滚后仍不治理，新发布才标记ENFORCED_BLOCK2。全量旧版本兼容仅针对当前schemaVersion=1受控载荷。
5. 启动：先停止IDEA旧JVM，再用Java17构建；Docker本地依赖保持启动。分别运行Demo/Control/Gateway主类，Control与Gateway运行配置选FlowGate根目录`.env`。测试入口`http://127.0.0.1:8081/test-console/`；浏览器刷新加载新静态资源。具体点击路径见`docs/guides/test-console-guide.md`。
6. 部分早期失败实验使用Docker默认匿名卷；后续清理已修正为只对自建容器执行`docker rm -f -v`。不为清理实验残留执行全局volume prune，避免影响用户数据。

**交付结论：** 功能块2编码与上述定向/真实链路验证已完成；真实浏览器人工验收及ChatGPT/负责人最终审核待完成，尚不宣称满足全部最终通过标准。只推送当前功能分支，不合并main、不进入Block3。

### 12.4 远端交付实际结果

2026-11-06：实现提交`618aef4`、`d89f152`，脚本与证据交付提交`59c7b59`已保存在本地。执行`git push -u origin feat/block2-traffic-governance`失败：`Permission to Felix0410/FlowGate.git denied to Felix0410`，HTTP403。**未推送成功，未建立块2PR，不把本地提交写成已在GitHub交付。** 没有改推main、强推、修改凭据或重写共享历史。具备仓库写权限后可重试同一功能分支。
