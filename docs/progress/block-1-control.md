# 功能块 1｜控制面、身份与动态路由发布

> 状态：待开始；功能块 0 验收通过后才授权编码。  
> 建议分支：feat/block1-control-publish。  
> 用途：独立的功能任务、技术实现和进度记录，Codex 不另做架构方案；历史设计见 README 和 docs/stage-0-audit.md。

## 1. 一次性完成的业务

新增独立 Spring MVC 控制面。管理员能管理服务、API、路由和基本策略，持久化草稿；管理员认证与角色权限；发布不可变配置版本；Gateway 在不重启的情况下拉取新版本并变更路由；可查询版本并回滚；记录操作审计。对受保护 API 实现 Caller Token 授权，演示 API 可以显式公开。

本块覆盖 B01、B02、B03、B05、B11、B12 基础能力；不做限流执行、并发治理、熔断、前端 UI、Kafka、Nacos、Redisson、商业计费或 OAuth2。

## 2. 工程模块和固定技术

沿用功能块 0 已测试的 Maven 版本基线：Java 17、Boot 4.0.9、Cloud 2025.1.4。增加 flowgate-control（独立 MVC）、必要时增加仅放 DTO/枚举的 flowgate-contract。

- Control 依赖：Spring Boot 4 WebMVC Starter、MyBatis-Plus Boot4 Starter、MySQL 驱动、Spring Data Redis、Validation。管理员密码哈希采用 spring-security-crypto 的 BCrypt，禁止自己实现密码哈希。
- Gateway 新增 Spring Data Redis Reactive 以查 Caller Token，会话校验必须非阻塞；本块不允许 Gateway 引入 Jdbc/Servlet/MVC。
- Control 包：controller（AdminAuthController、ServiceController、ApiController、RouteController、PolicyController、ReleaseController、CallerController）、service（IdentityService、CatalogService、ReleaseService）、security（AdminAuthInterceptor）、mapper、entity。
- Gateway 包：config（ActiveSnapshotStore、SnapshotPollingService）、routing（SnapshotRouteDefinitionLocator）、security（CallerAuthFilter）。先用 Spring 原有的 RouteDefinitionLocator、路由刷新事件与 WebClient，不新增配置中心和通用框架。
- SQL 文件：sql/flowgate-init.sql。使用最小 MySQL 模型和 MyBatis-Plus，暂不引入 Flyway/Liquibase。

## 3. 数据表字段与业务约束

| 表 | 首版核心字段 | 必须遵守的规则 |
|---|---|---|
| fg_service | id, code, name, upstream_uri, enabled | code 唯一，URI 白名单 |
| fg_api | id, service_id, method, public_path, upstream_path, is_public, enabled | 路径/方法冲突发布时校验 |
| fg_route | id, api_id, route_id, priority, enabled | route_id 唯一，首版一个 API 对应一路由 |
| fg_policy | id, code, type, scope, parameters_json, enabled | 本块存储/校验，不执行限流和熔断 |
| fg_policy_binding | id, policy_id, target_type, target_id | 引用存在且作用域匹配 |
| fg_principal | id, principal_type, username, password_hash, role, scopes_json, enabled | 角色 Admin、Operator、Viewer、Caller |
| fg_config_state | id=1, current_version, optimistic_version | CAS 乐观更新控制发布竞争 |
| fg_config_release | id, version, snapshot_json, sha256, actor_id, rollback_from, created_at | version 唯一递增，快照不可修改 |
| fg_audit_log | id, actor_id, action, target_type, target_id, release_version, created_at, summary | 重要变更随事务写入，不存明文凭证 |
| fg_release_node | id, node_id, applied_version, checksum, last_seen_at | 同节点只能有一条当前生效记录 |

各表可增加必要审计时间和索引，但不自行拆出复杂 RBAC、通用任务、事件总线等表。初始管理员由环境变量提供用户名和 BCrypt 密码哈希，首次启动按需初始化；不设置写死的默认密码。Scope 首版直接用 JSON 数组，不造复杂权限平台。

## 4. HTTP 接口：按名称执行

管理端默认本机 8081，可配置覆盖；所有 /admin 接口只存在于 Control，Gateway 绝不代理 /admin 或 /internal。

| 接口 | 说明 | 角色 |
|---|---|---|
| POST /admin/auth/login | 用户名密码登录，返回 opaque Token | 匿名 |
| POST /admin/auth/logout | 撤销当前会话 | 已登录 |
| GET/POST /admin/services，PUT /admin/services/{id} | 服务查/建/改 | Viewer 读，Operator+ 写 |
| GET/POST /admin/apis，PUT /admin/apis/{id} | API 查/建/改 | Viewer 读，Operator+ 写 |
| GET/POST /admin/routes，PUT /admin/routes/{id} | 路由查/建/改 | Viewer 读，Operator+ 写 |
| GET/POST /admin/policies，PUT /admin/policies/{id} | 策略查/建/改 | Viewer 读，Operator+ 写 |
| POST /admin/policies/{id}/bindings | 策略绑定 | Operator+ |
| POST /admin/callers | 创建 Caller + 授权 Scope，返回一次性 Token | Admin |
| POST /admin/callers/{id}/revoke | 撤销 Caller Token | Admin |
| POST /admin/releases/publish | body: expectedVersion，发布完整配置 | Operator+ |
| POST /admin/releases/{version}/rollback | 旧快照重新发布为新版本 | Operator+ |
| GET /admin/releases、GET /admin/releases/current | 历史与当前版本 | Viewer+ |
| GET /admin/nodes、GET /admin/audits | 节点状态与操作审计 | Viewer+ |
| GET /internal/snapshots/current | 内部拉取当前完整快照 | 内部专用密钥 + 本机/内网 |
| POST /internal/nodes/applied | 节点上报 version、checksum、nodeId | 内部专用密钥 + 本机/内网 |

默认禁止其余管理路径。未登录返回 401，已登录权限不足 403，参数错误 400，预期版本冲突 409。管理员登录错误不能泄露用户是否存在。调用方凭证校验失败时，受保护 API 不得放行。

## 5. 认证、发布、同步的固定步骤

**身份方案**：SecureRandom 签发足够随机的 opaque Token；只将 Token 的 SHA-256 摘要放入 Redis Key，Value 记录 principalId、role、scopes 和 TTL。管理员登录会话约定 30 分钟、Caller Token 60 分钟（集中配置，不散落常量）。撤销时删除对应 Key；Token 只在签发响应中展示一次，不打印日志。Control 的 MVC Interceptor 验证管理权限；Gateway 以 Reactive Redis 验证非公开 API 的 Caller Scope，禁止沿用 Servlet ThreadLocal。Redis 认证失效时私有 API fail-closed。

**策略发布步骤**：
1. 校验 API/Route/Service 关系、Method/Path 语义冲突、Scope、URI、策略参数/绑定。
2. 在同一个 MySQL 事务中用 fg_config_state 的期望版本做 CAS，生成新 version，保存 snapshot_json 和 SHA-256、写审计并更新 current_version。旧版本不可编辑。回滚复制历史快照内容并生成更大版本号，rollback_from 指向来源。
3. 返回 persistedVersion 和 PERSISTED 状态，不能把数据库提交成功说成“各网关已生效”。

**配置快照字段**：schemaVersion、version、checksum、services、apis、routes、policies、bindings。先序列化不含 checksum 字段的固定 JSON 负载再算哈希；快照中的 route 字段必须包括 routeId、Method、Path、upstreamUri、isPublic、requiredScope、configVersion。未实现的治理策略在快照中标示为待执行，不能宣称限流或熔断已经生效。

**Gateway 最小同步机制**：
1. 每 5 秒使用 WebClient 非阻塞拉取内部快照，比较版本，验证 checksum 和 schemaVersion。Control 不可达/快照无效则保留旧的有效快照。
2. 将不可变路由定义交给自实现的 SnapshotRouteDefinitionLocator（不自写转发），使用 Spring Cloud Gateway 原有刷新机制；路由 metadata 携带 configVersion 和已发布的鉴权要求。旧版仍在途的请求不能套用错误的新版权限；验证框架缓存更新时机。
3. 成功后异步调用内部节点上报 API，Control 保存已生效版本。首次无可信规则时默认拒绝私有/未知路由；原 Demo 公共路由是否保留必须显式定义。

**安全范围**：首版只允许管理员注册受控的本地 Demo 上游（如 http://127.0.0.1:8085），或用户明确配置的允许地址列表；不开放任意 URL 代理，不允许注册管理接口或 Actuator 端点。控制面默认只监听可信本机/内部网络。

## 6. 仅本块定向测试与验收

- 构建 Control、Gateway 和直接受影响的 contract 模块；验证 Servlet/JDBC 没有进入 Gateway。
- MVC API：登录/退出，角色 Viewer、Operator、Admin 权限；Caller Token 签发、过期、撤销和 Scope 拒绝；密码不得记录明文。
- 数据：CRUD、路由路径冲突、非法 URI、策略错误参数、发布 CAS 并发冲突、历史版本不可改、回滚版本递增、审计记录。
- 实际链路：MySQL/Redis 就绪后配置一条 Demo API，发布 V1，Gateway 自动加载；修改为 V2 并验证新路由；回滚产生 V3 且行为恢复。
- 失败链路：Control 中断时 Gateway 仍使用旧快照；错误 checksum 不生效；未经授权路由不可访问。
- 不做全量回归、整个项目大压测、多节点大规模故障演练（最终统一测试阶段做）。

## 7. 本地 Codex 限制、进度回填

保持 Plus 技术复用的最小思路，不搬原业务和旧 Starter。复杂方法须有正确中文注释，长业务块按 1、2、3 分点。若编译兼容问题只做最小修正，不擅自修改产品方向。

| 项目 | 目前状态 |
|---|---|
| 前置：Block0 审核 | 待通过 |
| 代码与 DDL | 未开始 |
| 分支、Commit、PR | 无 |
| 管理/身份/发布/同步定向测试 | 未执行 |
| 注释与安全检查 | 未执行 |
| ChatGPT + 负责人审核 | 未开始 |

完成后在此回填真实文件、分支和提交、测试命令/结果、问题清单；停留在该功能块，不直接合并 main，不提前开发块 2。

**本块完成判定**：真实的服务/API/路由管理、安全身份验证、版本 CAS、快照校验、Gateway 无重启热更新与回滚均有定向测试证据。