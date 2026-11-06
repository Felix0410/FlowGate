# FlowGate V1.1 业务重定位与架构总设计：OpenAPI 开放与配额治理平台

> 状态：**设计提案，未实现、未验证、未合并**。2026-11-06。基于 test/block4-final-acceptance 分支 ec16efe0bb1a5a07824c82f2352789a6c95391b1 的只读源码核对。
>
> 这是原 FlowGate 网关 V1 的增量业务设计；先完成 V1 产品后台人工验收、完整回归及 PR 审核。未经负责人单独授权，不修改 main，不合并旧 PR，不把本设计描述成已上线功能。
>
> Codex 执行索引：CODEX_V11_OPENAPI_PROMPT.md；每个功能块单独的具体任务见 docs/progress/v11-{a,b,c,d}-*.md。

## 1. 定位与需求边界

**一句话：** 面向第三方开发者与企业客户，在一个统一入口开放多个下游 API，管理开发者应用、API 授权、调用套餐与月度配额，保护慢接口/热点接口，并提供可核对的用量记录及发布治理能力。

**目标用户**：平台管理员 ADMIN、运营人员 OPERATOR、只读 VIEWER；API 使用方为客户（Developer）和其应用（Application）。V1.1 首版仅提供**管理员代创建客户/应用/套餐**；第三方开发者自助门户、真实收费、结算、支付、合同、SLA 承诺不在本次范围内。

**真实场景**：客户 A 使用查询 API，客户 B 高频请求报表/推理等高成本 API。运营端通过认证、API Scope、速率限额、月度累计额度及在途并发控制，在资源有限时保护普通查询；同时回答“某客户何时开通了什么套餐、请求为什么被拒绝、扣过多少额度、异步统计有没有缺口”。

**不做的内容**：不把原有 Gateway 变成业务数据库代理、不从头实现 HTTP 栈、不做微服务商城/订单/支付、不引入 Nacos/Seata/分库分表/Redis 分布式锁作为必选、不做所谓全链路强一致计费、不将 Kafka 放入 API 热路径、不把额度“次/单位”描述为实际收费金额。

## 2. 从现有代码出发的明确复用与改动

已存在且应原样保持或最小扩展：
- flowgate-gateway：WebFlux+Reactor HTTP 转发；CallerAuthFilter 从 Redis 验证 Caller 的 principalId 和 Scope；AdmissionGlobalFilter 负责 RATE_LIMIT 及本机并发许可；RouteDefinitionLocator 冻结同版本 Route/Policy；Resilience4j 服务保护；SnapshotPollingService 原有拉取/回滚机制。
- flowgate-control：Spring MVC+MyBatis-Plus；fg_principal（ADMIN/CALLER）、IdentityService 一次性 Token 和撤销；fg_service/fg_api/fg_route/fg_policy/fg_policy_binding；ConfigRelease CAS 发布及 fg_audit_log；产品后台 /console/。
- flowgate-contract：快照协议、规则校验；flowgate-demo：快/慢/故障 API；SQL 基线 sql/flowgate-init.sql。
- 现有 RATE_LIMIT 是按时间补充的**速率预算**，**不是月度累计额度**。现有 Caller Token 是身份凭证，不等于客户/应用/订阅；现有 Micrometer 指标不是可结算用量流水。
- 现存 Breaker 旧版本实例累积与 V1 人工体验问题仍在，不因新业务设计而自动消失。

需要明确新增：
1. Control 侧领域：Developer、Application、Plan、Subscription、QuotaBalance、QuotaLedger、Outbox、UsageDaily、ConsumedEvent。
2. 网关侧只有对 **显式标记为 METERED 的路由** 新增非阻塞配额预准入；未标记路由与现有 V1 行为保持一致。
3. Control 管理 API、内部配额 API、Kafka 可选消费者/投递器；后台新增“开发者与应用、套餐订阅、用量与异常”导航，保留原服务/API/路由、治理/发布/节点。
4. 数据面与控制面职责不逆转：Gateway **绝不直接访问 MySQL/JDBC/同步 Redis**；可以通过有上限的异步 WebClient 访问专门的内部配额 API。Control 的 SQL 允许同步执行，承认这增加了被计量 API 的时延和控制面可用性依赖；基础路由不受影响。

### 2.1 源码改动对照

| 现有位置 | 计划变更 | 不可破坏 |
|---|---|---|
| sql/flowgate-init.sql | **不覆盖旧表**，另建 sql/flowgate-v11.sql 幂等增量 DDL | 既有数据及 V1 初始化流程 |
| flowgate-control/service/IdentityService | 为应用关联已有 CALLER principal，保留一次性 Token、摘要索引、撤销 | ADMIN/CALLER 用途隔离、旧调用方 |
| flowgate-control/controller/* | 新增 Developer/App/Plan/Subscription/UsageController 与内部 QuotaController | /admin 现有协议、RBAC |
| flowgate-control/service/ReleaseService | 只在显式计量策略确需发布时扩展策略校验/快照；不要改变既有 CAS | 旧快照能读取、checksum、回滚 |
| flowgate-contract/PolicyRules 等 | 新增独立计量策略类型/参数；制定协议版本向后兼容 | 旧 RATE_LIMIT、CONCURRENCY、TIMEOUT、BREAKER |
| flowgate-gateway/security/CallerAuthFilter | 向后兼容复用可信 principalId，不读取客户端伪造 appId | 401/403、Redis 故障 fail-closed |
| flowgate-gateway/admission | 在已认证、原速率和并发检查之后增加一次计量准入；避免重复扣量 | Complete/Error/Cancel 许可释放 |
| flowgate-control/static/console/* | 产品业务表单、列表、状态与使用指南同步更新 | 不把正式 UI 变回开发测试台 |
| compose.local.yml | MQ 模块交付时才按需增加 Kafka 单机本地服务 | MySQL/Redis 旧启动流程仍可用 |

## 3. 业务对象及状态定义（只实现真正需要的关系）

- Developer（客户）：开发者/企业主体；状态 ACTIVE、SUSPENDED。管理员创建，不存支付信息。
- Application（应用）：从属一个 Developer，绑定一个或多个现有 Caller principal（首版限制一个有效凭证）；状态 ACTIVE、SUSPENDED；一次性展示 Token，绝不反查明文。
- ApiPlan（套餐）：code/name、每 UTC 自然月的累计准入预算（整数单位）、启停状态；管理员管理，套餐发布后不可直接改写既有订阅历史语义。
- Subscription（订阅）：应用绑定一个套餐，status ACTIVE/SUSPENDED/EXPIRED、start/end；每应用同时最多一个有效订阅；月度额度以首次创建期间账本时的套餐版本冻结；升级/降级默认**下个 UTC 自然月生效**，不要悄悄修改已消费的额度。
- Metered API（计量 API）：使用新增 USAGE_QUOTA 类型策略，限绑定 API 或 ROUTE，参数 unitsPerCall 为正整数，首版 1、可扩展 5；绑定到 protected API，不允许 public 路由计量；同一路由首版至多一条计量策略。
- QuotaBalance（期间余额）：唯一键 application_id+UTC年月，字段 total_units、remaining_units、版本；只允许数据库原子更新，满足 0<=remaining<=total。
- QuotaLedger（配额消费流水）：charge_id 唯一，可信 appId/principalId/apiId/routeId、计量单位数、UTC 月份、创建时间。V1.1 的单位是“**准入授权单位**”，不是业务成功次数，也不是收费金额。
- OutboxEvent：消费流水写入同一个 MySQL 事务的待发送事件。UsageDaily/ConsumedEvent 负责异步用量汇总/幂等记录。

**强制区分三类数据：**
1. RATE_LIMIT：Redis 令牌桶，限制瞬时到达率，失败可返回 429。
2. USAGE_QUOTA：MySQL 持久额度账本，限制周期累计允许授予的单位，耗尽返回 429 QUOTA_EXHAUSTED。
3. USAGE_STATS：Kafka 异步聚合的读模型，可有短暂延迟；不能以未聚合统计值判定能否继续调用。

## 4. 首版最关键的一致性取舍（明确不伪造强一致性）

**决定：V1.1 初版以 MySQL 为累计额度的唯一权威事实来源。** 被计量路由在原有 Redis 限速之后，经 WebClient 调用内部配额服务；Control 在单个数据库事务里核验应用、订阅、Scope/绑定关系、当前 UTC 期间并执行原子余额扣减、插入唯一 charge_id 流水、插入 Outbox 事件。事务提交后才放行该被计量请求。

1. 不选择“Redis Lua 扣月度额度，随后尽力把消息丢给 Kafka”作为权威扣减：Redis 扣量和 MySQL/Kafka 不能原子提交，一旦在两者间宕机就可能产生无法恢复的空洞。
2. Redis 保留原有 Lua 速率限制、Token 会话，以及只读套餐/元数据 Cache Aside（用于界面/目录查询，不作为最终授权或余额事实）。更新 MySQL 后在事务成功回调中删缓存，短 TTL；删除失败记录待补偿任务。读到缓存旧值不能改变计量授权结果。
3. 对同一 app+月份用数据库唯一键、条件 UPDATE remaining_units >= units、防重复 charge_id 及事务保证不超额与重复扣量；不依赖跨数据库/Redis 的幻想式事务。
4. Gateway 每次新的计量尝试生成内部 chargeId（不可接受客户端传入的 appId/principalId/chargeId 作为权威）。若内部 HTTP 重试，**必须复用同一个 chargeId**；客户端新发一次请求视为新的尝试，默认再次计量。Control 若已提交但响应丢失，重试相同 chargeId 返回历史结果，不能再次扣减。
5. **计量语义锁定**：一旦配额服务事务成功授予准入额度，即消费 unitsPerCall；后续下游 2xx、4xx、5xx、超时与客户端取消均**不自动退回**。原因是本次项目做的是“准入容量配额”，不是“只对成功调用计费”。即使网关在获准后、真正代理前崩溃，这次授予仍被计为已用；管理界面必须明确标注，不得宣传为“成功调用次数”或按金额扣费。若未来改为成功计费，必须另立 RESERVE/SETTLE/REFUND 状态机及超时恢复设计。
6. 为降低不必要的消费，在 Gateway 认证、现有速率和本机并发许可之后执行配额预准入。熔断已 OPEN 的请求尽可能预先拒绝而不扣量；CircuitBreaker 内部状态仍可能并发变化，不能承诺无竞态计费准确。新链路增加明确拒绝原因。
7. Control/DB 不可用、内部配额超时或返回不确定结果：**只影响被计量路由，必须 fail-closed 503 QUOTA_UNAVAILABLE**；原有未计量路由继续按 V1 规则运行。不能偷偷放行或从 Redis 猜剩余额度。
8. 性能代价是每个被计量请求需要一次内部非阻塞 HTTP 调用和 Control MySQL 事务；**不宣称达到现有纯代理性能**。在出现实际瓶颈并完成测量以前，不实现 Redis 预分配额度/租约，因为后者会显著增加正确性与恢复成本。

### 4.1 信任边界、失效与安全

- Gateway→Control 内部 quota 接口必须独立于 /admin 对外管理 API，绑定受限内部网段/端口或至少验证环境变量服务身份密钥、TLS/隔离网络；不得经公开可配置路由代理该内部接口，也不得直接相信 Caller 传的 X-App-Id。服务密钥仅从环境注入，日志不记录凭证。
- Gateway 把**已验证**的 principalId、可信 route/API 标识、chargeId 与 appliedVersion 传给内部配额服务。Control 从数据库确认 principal→app 绑定、应用/客户/订阅状态、具体计量 API 和单位数；不能仅凭报文字段扣另一个应用余额。
- 控制面中的计量策略只允许在已发布快照上启用；Gateway 解析同版本规则；Rule/Route 构造需要显式支持新策略，不能套用现有 RateLimit 的 capacity/refill 字段。
- 下线应用的计量访问立即在数据库授权步骤被拒绝；同时尽最大可能撤销已有 Redis Caller Token（DB+Redis 不可宣称跨系统原子）。历史非计量路由需要明确处理应用凭证：首版**应用专用 Token 只允许对带 USAGE_QUOTA 的受保护路由使用**；旧 CALLER 的既有路由调用规则不变，防止应用被停用后通过旧非计量路由绕开账本。**分阶段安全门槛：A 阶段只实现应用/主体映射及凭证入口但不签发可用 app Token（返回 FEATURE_NOT_READY），B 阶段 app-only 策略与配额授权同时上线后才允许首次签发。**
- 配额授权建议 403 APP_DISABLED/NO_ACTIVE_SUBSCRIPTION；额度不足 429 QUOTA_EXHAUSTED；内部依赖故障 503 QUOTA_UNAVAILABLE；未知业务条件不要吞成 200。错误体沿用现有 requestId 语义。
- UTC 期间边界按数据库 UTC 时钟/明确统一 clock 决定（不得信任客户端日期）；允许测试时注入时钟，跨时区和月末并发均有断言；不能用 TTL 当作唯一的余额重置机制。
- 管理端严禁普通 VIEWER 改配额；所有变更与关键操作应入审计，Caller Token 明文仅创建/轮换时一次展示且不进入 Kafka 或 Git 日志。
- 不采集 API 请求 body/Authorization 到 Outbox/Kafka，事件只含最小计量字段；避免高基数 applicationId 作为 Micrometer 常规 Tag，管理端统计由 DB 查询。

## 5. 新增表建议与约束（详细 DDL 在 B 任务书落实）

| 表 | 最少字段与约束 | 用途 |
|---|---|---|
| fg_developer | id,code UNIQUE,name,status,created_at | 客户主体 |
| fg_application | id,developer_id,code UNIQUE,name,status,created_at | 第三方应用 |
| fg_app_principal | principal_id UNIQUE,application_id,index(application_id) | 关联原有 CALLER；不复制 Token |
| fg_api_plan | id,code UNIQUE,name,monthly_units,version,status | 套餐定义 |
| fg_subscription | id,application_id,plan_id,effective_at,expires_at,status,version；有效订阅互斥由事务保证 | 应用与套餐 |
| fg_quota_balance | id,application_id,period_yyyymm,total_units,remaining_units,version, UNIQUE(app,period) | 权威额度余额 |
| fg_quota_ledger | id,charge_id UNIQUE,application_id,principal_id,api_id,route_id,period_yyyymm,units,created_at | 权威消费事实 |
| fg_outbox_event | id,event_id UNIQUE,event_type,payload_json,status,next_attempt_at,attempt_count,created_at | 同事务可靠消息出口 |
| fg_consumed_event | event_id PRIMARY KEY,processed_at | 消费幂等 |
| fg_usage_daily | date_utc,application_id,api_id,units,request_count；组合主键 | 用量读模型 |

- 统一 BIGINT 主键，金额完全不涉及；单位数要有正整数/上限检查，避免溢出。
- 新建 sql/flowgate-v11.sql 只 CREATE IF NOT EXISTS，不删除旧数据、无 destructive ALTER。需要为旧 fg_api/fg_policy 增字段时必须有独立兼容迁移及回滚路径，优先通过新增 USAGE_QUOTA 策略避免更改 fg_api 表。
- 表名、字段名、索引应以 Mapper 实际查询和 EXPLAIN 证据最终确定；不能只按表清单盲建索引。
- 并发下首次初始化期间余额须防重复创建；订阅在周期内被改变时按冻结的已授予额度处理，不隐式清零或补发。

## 6. API 合同草案（所有接口都需要列表/空态/权限/失败响应）

### 6.1 管理员与运营端，沿用当前 /admin Session

- GET/POST /admin/developers、GET/PATCH /admin/developers/{id}
- GET/POST /admin/apps、GET/PATCH /admin/apps/{id}
- POST /admin/apps/{id}/credentials：为 app 绑定新 Caller 并返回**一次性** Token；禁止列表回显明文；轮换/吊销须考虑旧 Token 清除
- GET/POST /admin/plans、GET/PATCH /admin/plans/{id}
- GET/POST /admin/subscriptions、GET /admin/subscriptions/{id}
- GET /admin/usage?applicationId=&from=&to=&cursor=&limit=（从统计读模型查询并显示延迟）
- GET /admin/quota/balances?applicationId=&period= 与 GET /admin/quota/ledger?applicationId=&period=&cursor=&limit=
- GET /admin/async-events?status=&cursor=&limit=（仅管理员/运营，敏感信息脱敏）

RBAC：ADMIN 管理客户/应用/套餐/订阅/凭证；OPERATOR 查看并执行已有授权的运营动作，是否允许套餐分配需明确权限表；VIEWER 全部只读；Caller Token 不能访问 /admin。新增请求全部校验合法 id、代码、期限、分页大小与最大区间。

### 6.2 可信内部端点

- POST /internal/quota/charge
  请求仅允许 Gateway 服务身份：{chargeId,principalId,apiId,routeId,appliedVersion,units}；
  成功 {chargeId,chargedUnits,period,remainingUnits,duplicate}，不泄露他人余额；
  失败 403/409/429/503 按合同区分；同 chargeId 不同有效负载一律 409 IDEMPOTENCY_CONFLICT。
- 任何网络自动重试须有明确上限、同一 chargeId、短总超时，不在 Netty 线程上 block；不能对下游有副作用的 HTTP 请求自动重试。

### 6.3 原有 API 兼容性

- 不改 /admin/auth/login、/admin/services、/admin/apis、/admin/routes、/admin/policies、/admin/releases 与 /test-console/ 行为。
- 新增 USAGE_QUOTA 时在 PolicyRules 验证配置与绑定；旧版本 PolicyType 解释保持一致，旧发布快照可恢复。
- 路由显式开启 METERED 前，即使创建了套餐也不能影响原有 V1 流量；所有未计量路由继续使用现有规则。

## 7. Kafka 与 Outbox 设计（只在模块 C 引入）

**事件主题**：flowgate.usage.v1，Key=applicationId，Value 最小字段：eventId,chargeId,applicationId,apiId,periodUtc,units,eventTime,version；无任何 Token/请求体/PII。

1. MySQL 成功扣减时同事务写 fg_quota_ledger + fg_outbox_event。发布失败不影响已扣减事实，Outbox 保持待发送。
2. 定时投递器使用 MySQL 8.4 的 SELECT ... FOR UPDATE SKIP LOCKED 领取已到期事件；它只适合队列领取，不应用于账本一致性查询。多 worker 不应长期持有数据库事务等待 Kafka ack；须使用有超时的状态领取/租约与失败可重领策略。
3. Kafka ack 成功后更新事件已发。若 Kafka 已收而进程在更新状态前崩溃，可重复发送；接受**至少一次投递**。
4. 消费者在同一 MySQL 事务尝试插入 fg_consumed_event(event_id UNIQUE)，成功才更新 fg_usage_daily，重复事件不再加总；事务提交后再提交 Kafka offset。
5. 消费失败按有界退避重试、超过阈值进入持久失败队列或 DLT；后台可查看/手动安全重投，不得吞掉错误后继续提交 offset。
6. 账本与异步日统计是两个视图，后台展示“账本权威余额/用量”与“异步统计更新时间”，不能把二者短暂不等说成强一致性缺陷。对账任务按日或手工比较 ledger SUM 与 usage_daily 并提示缺口，修复幂等、可追踪。
7. MQ 下线时核心扣减仍可完成（因 Outbox 已落 MySQL）；但 Outbox 堆积要告警/限量，不能无界增长。Kafka 不可用也不影响未计量普通网关业务。

## 8. 真实业务与人工体验设计

正式 /console/ 导航：
- **工作台**：当日 API 请求、授权失败/限速/配额拒绝、节点应用版本、异步积压、重要告警。
- **开放 API**：沿用服务/API/路由管理，显示是否计量、单位成本、关联保护策略与生效版本。
- **客户与应用**：客户列表、应用创建、状态、凭证一次性显示/轮换、Scope、当前订阅。
- **套餐与配额**：套餐规则、订阅、当前月份累计额度、余额、耗尽情况、历史账本。
- **用量与审计**：时间范围/客户/API 聚合、明细游标分页、异步统计延迟、失败事件与对账。
- **策略发布/节点监控**：沿用 V1 页面，不复制一套发布中心。
- 375px 与 1280px 可用；所有“保存”与“发布”语义清楚；状态、失败、空数据、帮助、权限提示；不要再出现业务使用者必须填写 JSON 或裸 ID 的页面。

演示闭环（真实 HTTP）：
1. 管理员创建 Developer A/B、Application A/B、套餐 Free(每月5单位)/Pro(每月20单位)，为 App 关联合法 Caller Scope。
2. 注册一个受保护的查询 API 和一个慢 API；只为需要消费额度的 API 绑定 USAGE_QUOTA；发布新版本，观察两个 Gateway 同版本应用。
3. A 连续请求 6 次，前 5 次授权准入并写 ledger，第 6 次返回 QUOTA_EXHAUSTED；B 仍可成功。用带限速的热点场景观测不同拒绝原因。
4. 注入重复 chargeId、数据库提交后内部响应丢失、Redis/MQ/Control 故障，验证无重复余额扣减、被计量路由 fail-closed、普通路由保持 V1 行为。
5. 消费者重启与重复 Kafka 事件后，UsageDaily 与 Ledger 对账一致；展示失败消息与重试；管理员查看日志、调整套餐并按规则下月生效。
6. 在三个业务负载（查询/慢请求/高 CPU 或连接池资源争用）下比较直连、纯代理、仅流量治理、启用计量四组；**计量会新增 DB/HTTP 成本，应记录延迟和吞吐变化，不能复用 V1 数字冒充新实验**。

## 9. 质量与验收的硬条件

- 单独功能块只跑新增和直接受影响测试，每块一条最小真实 HTTP 联调；最终统一跑 JDK17 mvn verify、双网关回归、异步故障、浏览器手工、稳定性/性能对照。
- 事务断言：并发超额绝不产生负余额；chargeId 重试只扣一次；同 chargeId 不同内容拒绝；Outbox 与 Ledger 同事务；消费者重复只汇总一次；月底两个 Gateway 并发期间不会穿越到错月。
- 故障断言：Redis 缓存删除失败补偿与 TTL；MySQL 不可用 fail-closed；Kafka 下线不丢 Outbox；消费者提交成功但 offset 未提交不重复聚合；服务密钥非法拒绝；未计量旧路线完全回归。
- 性能要记录已成功的 API 业务调用数、被拒数、真实 DB 查询/锁等待与控制面 CPU、Gateway 成功 P95/P99、端到端计量开销；禁止用 CLIENT_SHED 冒充网关拒绝。
- SQL 生成非敏感模拟数据，至少 10万级审计/流水规模做 EXPLAIN ANALYZE、游标分页与 offset 对照；优化前后都记录运行条件与原始指标。
- 所有新增代码中文注释，复杂长逻辑按 // 1. // 2. // 3. 分点；不引入无业务需求的技术栈，不上传 .env/Token/日志/数据卷。
- 不进行生产级计费或宣称“全局 exactly once”；首版严格限制为配额准入系统，Kafka/Redis 只是业务实现手段。

## 10. 分阶段交付与停止标准

| 顺序 | 功能块 | 验收目标 |
|---|---|---|
| 前置 | V1 收口 | 人工产品体验、全量回归、既有 PR 审核；明确继续基线 |
| V11-A | 客户、应用、套餐、订阅与 UI | 能独立管理员配置两客户/两套餐/应用凭证，旧路由正常 |
| V11-B | 累计配额与可信数据面调用 | 双网关 5 次耗尽/第6次拒绝，账本事务与幂等可证 |
| V11-C | Outbox+Kafka 异步用量与对账 | Kafka 宕机/重启/重复事件后账本与统计最终收敛 |
| V11-D | SQL 性能、产品体验、最终验收 | 10万级数据与 EXPLAIN、四组性能、手工验收、文档与 PR |

**按块确认与提交**：每块独立进度文件、具体测试和真实结果，Codex 只做明确的编码任务；不因新增业务改变原网关 V1 的重要正确性承诺。每次变更同步 /console/，包括菜单和指南。

## 11. 参考与工程判断依据

- MySQL 8.4 SELECT ... FOR UPDATE SKIP LOCKED 的队列领取适用性与局限：https://dev.mysql.com/doc/refman/8.4/en/select.html
- Apache Kafka 官方投递语义：https://kafka.apache.org/40/design/design/
- 项目现有源码：flowgate-control/service/IdentityService、ReleaseService；flowgate-gateway/security/CallerAuthFilter、admission/AdmissionGlobalFilter、policy/EffectivePolicyResolver；sql/flowgate-init.sql。
