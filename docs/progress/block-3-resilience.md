# 功能块 3｜故障保护、配置恢复与运行监控

> 状态：待开始；必须先通过功能块 2 审核。  
> 建议分支：feat/block3-resilience-observability。  
> 本文件负责实施方案与进度，不重新做架构决策；最终统一大测试另有独立文件。

## 1. 目标

在既有动态路由、认证、限流和并发保护上，实现下游超时、熔断、受控降级；补齐多节点配置最终一致性与故障恢复，完善网关统计和节点生效版本查询。覆盖 B09、B10、B12 完整功能。

不引入 Nacos、Sentinel、Kafka、Redisson、Elasticsearch、自研复杂熔断器或整套 Kubernetes/Prometheus 平台。熔断优先用 Gateway 官方支持的 CircuitBreaker Filter 与其必要实现，避免手写状态机；监控优先使用 Micrometer + Actuator。

## 2. 文件和组件落点

- Control：在现有 PolicyService、ReleaseService 中补充 TIMEOUT、CIRCUIT_BREAKER、FALLBACK 参数校验，并在 GET /admin/nodes 展示实际生效版本与最后上报时间；无须新建微服务。
- Gateway protection：Gateway 原生配置转化/适配器（Timeout/CircuitBreaker/Fallback），将控制面的生效版本策略映射到 Spring Cloud Gateway 官方支持的配置；不要将所有规则写死到手工实现的 HTTP 代理。
- Gateway config：复用功能块 1 的 ActiveSnapshotStore、SnapshotPollingService、版本校验、周期补拉及 Node Acknowledge；增加状态记录及故障日志即可。
- Gateway metrics：Micrometer 中增加有限、低基数的 outcome、routeId、policyType、rejectReason 等指标（可使用现有指标，避免重复上报）。
- Demo：增加或利用独立的故障控制接口/参数，用于返回可控 5xx、超时及恢复；仅在本地演示环境开放，不能通过外部参数造成任意长延迟或内存占用。

## 3. 固定超时与熔断业务语义

**超时**：支持下游连接超时和响应超时，参数必须大于 0 并有明确上限；先采用 Gateway/HTTP Client 原生能力。超时返回有意义的网关错误（通常 504）；下游主动 500 应尽量保留，不能篡改为网关 200。有效超时时间以路由生效版本决定。

**熔断**：对故障率和慢请求率提供策略参数，如 slidingWindowSize、minimumNumberOfCalls、failureRateThreshold、slowCallDurationThreshold、waitDurationInOpenState、permittedNumberOfCallsInHalfOpenState。初期默认先支持错误率阈值和半开恢复，慢调用率确有必要时再暴露并验证。使用 Gateway CircuitBreaker Filter/Resilience4j 的最小依赖；不要自写一套新的 CircuitBreaker 运行时。

1. Closed：允许请求并在窗口中统计失败。
2. Open：达到阈值后短路请求，直接返回 503（或明确已配置的拒绝码），不再转发给故障下游。
3. Half-Open：过等待期后仅少量探测请求，通过后关闭；继续失败则再打开。状态变化有日志和 Micrometer 计数。

**降级**：首版只允许显式配置的只读 GET 路由有固定、清楚标记的降级 JSON；不把 POST/PUT 等有副作用的操作自动重试或返回伪造成功。不提供通用自定义脚本引擎。对外降级响应应明确带 degraded=true 或等效字段，便于调用方区分真实和降级结果。

**过滤器先后顺序**：按业务约束验证：匹配 Route/配置版本 → 必要认证与授权 → 限速 → 在途许可 → 熔断/超时/转发 → 所有终止分支释放许可 → 指标。具体 Filter 的真实执行次序用相关集成测试确认，不靠注释猜测。

## 4. 配置和故障模式

**当前快照**：继续采用 MySQL 发布权威版本 + Gateway 非阻塞周期拉取 + checksum + 完整快照验证 + 原子替换/路由刷新，不新增配置中心。新增版本与节点状态监控。

1. Control 不可用：Gateway 保持最近有效快照并继续代理已有授权 API；无法拉取新规则时记录“节点版本落后”，不能宣称已升级成功。
2. Gateway 重启：有 Control 可用则拉取最新可信快照后提供受保护 API；无法获得可信快照时必须 fail-closed；已提交规则不能被临时匿名动态地址绕过。
3. Redis 不可用：**认证**无法校验则受保护 API fail-closed；**速率限制**对所有受限路由默认 503，不允许静默放行。若要给公开只读接口增加降级的本地保守限额，必须明确配置并单独验证，首版可不做此扩展。
4. 节点短暂断网后重连：5 秒周期补拉发现高版本，拉取验证通过后更新并上报。配置通知不是必要依赖，防丢消息；不要为此引 Kafka。
5. 非法版本：校验 checksum、必需字段、未授权 URI、冲突的 Path/Method；失败时保留旧版本并有可查的错误记录。历史快照在途请求须保持其对应版本，不能误用不相关新规则。

**权限与安全**：只有内网 Control 获取节点状态；Actuator 只对可信网络/本机开放，不能启用网关动态路由写入端点。不得通过请求头或 JSON 参数选择任意后端 URL。

## 5. 指标与展示（不加复杂 UI）

用已有 Actuator / Micrometer 提供以下基础观测，可通过 /actuator/metrics 和 Control 管理 API 查看；没有强制要求 Grafana/Prometheus：

| 指标 | 标签及说明 |
|---|---|
| gateway 请求耗时/状态 | 复用框架 spring.cloud.gateway.requests，不重复创建同名 Metric |
| admission 限流拒绝 | routeId、policyType、reason，计数 |
| concurrency 在途数/超额拒绝 | routeId 或 serviceId（低基数），Gauge / Counter |
| circuitbreaker 状态与拒绝 | routeId、outcome，必要计数 |
| upstream 超时、5xx | routeId、outcome |
| config 当前版本与节点最后应用时间 | 节点级管理状态；不要把原始用户信息放到 Metric 标签 |

Path 标签保持默认关闭；不能使用原始用户 ID、Token、Query 或完整动态 Path 作为 Metrics label。对于原始客户端请求与真正网关失败，统计口径应有明确区分。

## 6. 本块定向测试

- 注入 Demo 500/503/超时，验证正确 HTTP 状态、熔断 Closed/Open/Half-Open 状态和恢复，确认 Open 时下游不再被持续调用。
- GET 降级有明确标识；POST/PUT 不产生伪造成功或自动重试。
- 使用一个服务并发许可配合超时/取消，确认所有终止路径都释放许可，不因熔断增加漏许可。
- Control 停止/恢复：旧已校验快照继续运行，新版本不被误报已生效；恢复后追平并上报。
- Redis 短暂故障：私有鉴权及限速失败有明确预期，没有 silent fail-open。
- 指标可查、低基数、不包含敏感信息；状态码、版本变化、熔断计数对应测试事件。
- 多 Gateway 节点的小范围配置同步/故障演练即可，不需要此时做正式大压测；不运行无关全量旧测试。

## 7. 注释、进度、停止

本块复杂状态转换、版本拉取、失效恢复、Filter 取消清理必须有准确中文注释，长方法/流程按 // 1.、// 2.、// 3. 分点；不要堆技术和过度分层。不得擅自进入最终统一验收块。

| 项目 | 当前 |
|---|---|
| 前置：Block2 审核 | 待通过 |
| 熔断与降级 | 未开始 |
| 配置恢复与失败模式 | 未开始 |
| 指标与节点状态 | 未开始 |
| Git 分支与提交 | 无 |
| 定向测试 | 未执行 |
| ChatGPT + 负责人审核 | 未开始 |

实现完成后更新实际改动文件、依赖、定向测试命令与结果、风险及 SHA；仅推送分支，等待复核。

**完成标准**：下游故障和恢复状态正确，配置断联不丢合法旧规则，认证/限流故障可预期，监控足以解释决策结果，中文注释与本块定向测试合格。