# FlowGate 功能块 1｜控制面、身份认证、动态路由发布与简易测试前端

> 文档版本：V2.0（2026-11-05）  
> 状态：**设计及 Codex 实施契约已完成；代码未开始，本块测试未执行，人工验收未进行**。  
> 实施分支：`feat/block1-control-publish`（已在 GitHub 创建；本地 Codex 必须先检出该远端分支）。  
> 唯一实施契约：**本文件**；全阶段前端规范见 `docs/standards/frontend-sync-rule.md`。  
> 前置事实：Block 0 已实现独立 Gateway + Demo、静态代理、Trace ID、Actuator 指标；按仓库记录已通过本地定向测试和静态代码审核，但负责人尚未手动验收。根据负责人新指示，**不要求先完成人工测试，统一在最小可执行 Demo 形成后手动验收**。

## 1. 本块定位和最终交付

### 1.1 业务闭环

管理员登录 Control → 录入受控服务与 API → 保存路由/策略草稿 → 通过 MySQL CAS 发布不可变版本 → Gateway 非阻塞拉取并验证版本 → 不重启即按新路由与鉴权配置转发 → 查看节点应用版本 → 对历史版本执行回滚（形成新版本）→ 查看审计。调用方使用独立 Caller Token 访问私有 API。一个极简网页覆盖这些操作，并兼容原 Block 0 的三个 Demo 测试。

本块落实 B01、B02、B03、B05、B11、B12 的基础部分；B04 和 B10 延续块 0 的能力。

**完成定义**：三个 JVM（Gateway 8080、Control 8081、Demo 8085）+ MySQL + Redis 在本地运行；网页能完成登录、路由版本 V1→V2→V3 回滚、公开/受保护 API 验证和三个既有 Demo 请求；所有本块定向测试得到可复核记录。届时负责人进行第一次统一手动测试。

### 1.2 明确不做

不做真正限流/熔断/并发许可、全功能 RBAC 平台、多租户、OAuth2、Kafka、Nacos、Redisson、K8s、微前端、Vue/React、Node 构建链、复杂图表或大规模压测；这些均不得成为本块完成的前置条件。策略表可管理并进入快照，**本阶段策略只配置/校验，不执行**，网页必须明确标为“待功能块 2/3 生效”。

## 2. 技术边界与目录

- 沿用 Java 17、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Gateway Server WebFlux、Maven。
- 新增 `flowgate-control`：Spring Boot 4 `spring-boot-starter-webmvc`、MyBatis-Plus Boot4 Starter、MySQL Driver、`spring-boot-starter-data-redis`、Validation、`spring-security-crypto`（仅 BCrypt 工具，不加入完整 Spring Security 过滤链）；沿用已有测试 Starter。
- Gateway 增加 **Reactive Redis** 和 Spring WebFlux WebClient（若 WebFlux 已传递依赖则不重复声明）；只在 Gateway 使用 `ReactiveStringRedisTemplate`。**禁止** JDBC、阻塞 Redis、Servlet、同步 Redisson 进入 Gateway 热路径。
- 可选 `flowgate-contract`：仅当确实减少多个模块之间的快照 DTO 重复时新增；只放纯 Java DTO、枚举及序列化契约，不加入 Web MVC/Redis/JDBC。
- 本块不引入 Flyway/Liquibase。使用明确可重复执行的 `sql/flowgate-init.sql` 和单独示例数据 SQL/步骤，避免意外覆写正式数据。

建议最小目录（文件可以按实际拆分，禁止空抽象层）：

```text
pom.xml                         # 增加 control；contract 只有必要时增加
flowgate-control/
  pom.xml
  src/main/java/com/flowgate/control/
    FlowGateControlApplication.java
    controller/                  # Auth、Service、Api、Route、Policy、Caller、Release、Node/Audit
    service/                     # 身份、目录管理、发布服务
    security/                    # MVC 管理认证拦截器与内部接口校验
    mapper/ entity/ dto/         # 控制面持久化与输入输出
  src/main/resources/
    application.yml
    static/test-console/
      index.html                 # 唯一测试台页面
      style.css
      app.js
flowgate-gateway/src/main/java/com/flowgate/gateway/
  config/ActiveSnapshotStore.java
  config/SnapshotPollingService.java
  routing/SnapshotRouteDefinitionLocator.java
  security/CallerAuthFilter.java
sql/flowgate-init.sql
docs/progress/block-1-control.md
docs/standards/frontend-sync-rule.md
scripts/block1-smoke.py           # 标准库脚本，或等价轻量脚本
```

**不要为每张表制造独立的复杂 Repository/Adapter/Facade 层。** Controller/Service/Mapper 直接按业务职责组织；已有普通 CRUD 可集中实现。所有网络地址、密码、白名单、内部密钥使用环境变量，不提交真实秘密。

## 3. 部署、本机地址与依赖

| 服务 | 默认本机地址 | 用途 |
|---|---|---|
| Gateway | `http://127.0.0.1:8080` | 业务 API 的唯一公共入口；现有 `/actuator/health`、`/actuator/metrics` 延续 |
| Control | `http://127.0.0.1:8081` | `/admin/**`、`/internal/**`、`/test-console/` |
| Demo | `http://127.0.0.1:8085` | `/demo/fast/{id}`、`/demo/echo`、`/demo/slow` 原样保留 |
| MySQL | 环境配置 | API/服务/策略/快照版本/审计 |
| Redis | 环境配置 | opaque Token 及其吊销索引；网关非阻塞读 |

Control 只绑定 127.0.0.1（演示环境）；内部接口需要 `X-FlowGate-Internal-Key` 与 `FLOWGATE_INTERNAL_KEY`，Gateway 从自身环境变量获取，不向浏览器发送。Control 不能在 Gateway 自动映射 `/admin/**`、`/internal/**`、`/test-console/**`。**禁止 wildcard 路由、任意公网 URL 或无白名单重定向。**

前端从 Control 同源加载，JavaScript 发 `/admin/**` 同源请求；访问 Gateway 8080 时仅允许固定开发来源 `http://127.0.0.1:8081` 的浏览器 CORS，请求使用 `Authorization: Bearer ...`；OPTIONS 预检只完成 CORS 协商，实际业务认证不可被跳过。不得设置 `Access-Control-Allow-Origin: *` 配合凭证。**前端绝不能包含内部密钥**。

## 4. 最小数据库模型（SQL 实施依据）

统一使用 `BIGINT` 自增或明确的稳定字符串 ID；`created_at`、`updated_at` 使用数据库时间；`code`、`route_id` 等自然键应有唯一索引。必要字段如下，不任意加表：

| 表 | 必需字段 | 规则 |
|---|---|---|
| `fg_service` | `id, code, name, upstream_uri, enabled` | code 唯一，URI 受白名单约束 |
| `fg_api` | `id, service_id, method, public_path, upstream_path, is_public, required_scope, enabled` | method 为 GET/POST/PUT/DELETE 等受控枚举；私有 API 的 `required_scope` 非空 |
| `fg_route` | `id, api_id, route_id, priority, enabled` | 一个 API 对应一条路由；route_id 唯一 |
| `fg_policy` | `id, code, type, scope, parameters_json, enabled` | 首版只支持受控枚举与 JSON 结构校验，均不运行 |
| `fg_policy_binding` | `id, policy_id, target_type, target_id` | 绑定目标存在且类型匹配 |
| `fg_principal` | `id, principal_type, username, password_hash, role, scopes_json, enabled` | ADMIN/OPERATOR/VIEWER 使用 BCrypt 密码；CALLER 不持久化明文 Token |
| `fg_config_state` | `id, current_version, optimistic_version` | 固定 id=1、初始版本 0；CAS 防止并发发布丢更新 |
| `fg_config_release` | `id, version, snapshot_json, sha256, actor_id, rollback_from, created_at` | version 唯一递增；已发布快照只读 |
| `fg_audit_log` | `id, actor_id, action, target_type, target_id, release_version, created_at, summary` | 重要变更审计；无原始凭证/密码 |
| `fg_release_node` | `id, node_id, applied_version, checksum, last_seen_at` | 节点 ID 唯一，仅报告实际加载成功的版本 |

MySQL 不保存任何明文 Token，也不允许将原始 Token 作为审计摘要。Control 启动时 `FG_BOOTSTRAP_ADMIN_USERNAME`、`FG_BOOTSTRAP_ADMIN_BCRYPT` 配置有效才在**管理员不存在时**初始化；不能覆盖既有管理员，也不能使用默认 admin/admin。必须在 README 给出本地生成 BCrypt 哈希的办法（可通过一次性的 Java/JUnit 生成器）和环境模板。不在源码仓库提交真实凭证。

### 4.1 API 与上游路径匹配

发布时以 `{HTTP method, public_path}` 唯一标识 API，禁止同方法相同路径、与框架匹配模式重叠、优先级歧义。首版只支持静态片段与单级 `{variable}`，尾部不支持 `/**`、通配上游 URL、regex；**Codex 禁止通过放松匹配规则来规避冲突**。

快照中的路由提供 `publicPath` 和 `upstreamPath`，可按受控模板变量替换，如 `/api/demo/fast/{id}` → `/demo/fast/{id}`。只允许模板中的同名变量、固定路径，不拼接用户提交的 Host、scheme 或查询参数。Query 原样转发；URL 编码与特殊字符必须有测试。Demo 的 POST Echo、slow 均为精确路径。

服务 URI 校验：仅 `http` / `https`，无 userinfo、query、fragment；精确匹配 `FLOWGATE_ALLOWED_UPSTREAMS` 的 `scheme://host:port` 列表。本机默认只允许 `http://127.0.0.1:8085`；禁止使用控制面自身地址、Gateway 地址、云元数据地址、Unix 协议、任意 127.0.0.0/8 绕过形式。校验放在**保存草稿和发布快照两处**；Gateway 再校验快照目标在本机允许列表中。生产多主机使用真实可信白名单，不从请求中取目标。

## 5. HTTP 接口和返回契约（需直接实现，不重新设计）

统一 JSON 错误：`{"code":"VALIDATION_ERROR","message":"中文可读说明","requestId":"..."}`。HTTP 状态按语义：400 参数/非法路径；401 未登录或凭证无效；403 已登录但无权；404 资源不存在；409 版本竞争或唯一键冲突；503 MySQL/Redis/控制面不可用。非预期异常不得回传 SQL/密码/令牌。

**所有 admin 接口**除登录外均需要 `Authorization: Bearer <adminToken>`。`VIEWER` 可读；`OPERATOR` 可读写目录和发布；`ADMIN` 额外管理 Caller。角色大小固定 `ADMIN > OPERATOR > VIEWER`；CALLER 不允许调用管理 API。登录用户名不存在与密码错误返回相同信息。

| 方法与路径 | 输入核心字段/输出 | 最低角色 |
|---|---|---|
| `POST /admin/auth/login` | `{username,password}` → `{token,expiresInSeconds:1800,role}` | 匿名 |
| `POST /admin/auth/logout` | 删除当前会话 → 204 | 已登录 |
| `GET /admin/services` | 列表，按 ID 稳定排序 | VIEWER |
| `POST /admin/services` / `PUT /admin/services/{id}` | `{code,name,upstreamUri,enabled}` → 完整实体 | OPERATOR |
| `GET /admin/apis` / `POST /admin/apis` / `PUT /admin/apis/{id}` | `{serviceId,method,publicPath,upstreamPath,isPublic,requiredScope,enabled}` | VIEWER / OPERATOR |
| `GET /admin/routes` / `POST /admin/routes` / `PUT /admin/routes/{id}` | `{apiId,routeId,priority,enabled}` | VIEWER / OPERATOR |
| `GET /admin/policies` / `POST /admin/policies` / `PUT /admin/policies/{id}` | `{code,type,scope,parametersJson,enabled}` | VIEWER / OPERATOR |
| `POST /admin/policies/{id}/bindings` | `{targetType,targetId}` → 绑定结果 | OPERATOR |
| `POST /admin/callers` | `{username,scopes:["demo:read",...]}` → `{id,token,expiresInSeconds:3600}`，Token 仅本次可见 | ADMIN |
| `POST /admin/callers/{id}/revoke` | 吊销该 Caller 当前 Token → 204 | ADMIN |
| `POST /admin/releases/publish` | `{expectedVersion:0}` → `{version:1,status:"PERSISTED",checksum}` | OPERATOR |
| `POST /admin/releases/{version}/rollback` | `{expectedVersion:2}` → `{version:3,status:"PERSISTED",rollbackFrom:1}` | OPERATOR |
| `GET /admin/releases` / `GET /admin/releases/current` | 历史版本列表 / 当前版本与 checksum | VIEWER |
| `GET /admin/nodes` / `GET /admin/audits` | 最近成功上报节点 / 审计列表（有限分页） | VIEWER |
| `GET /internal/snapshots/current` | 当前已发布完整快照 | 内部密钥 |
| `POST /internal/nodes/applied` | `{nodeId,version,checksum}` → 204 | 内部密钥 |

`GET /admin/releases/current` 在未发布时返回 `{version:0,status:"EMPTY"}`。GET 列表无记录时返回 `[]`。POST 创建 201，PUT 更新 200，成功删除/吊销 204；PUT 不接受修改不可变 ID 与已发布历史。接口的完整示例请求可放到轻量 `scripts/block1-requests.http`，不另外生成大型 OpenAPI 框架。

### 5.1 Session 和 Caller Token 的准确模型

- `SecureRandom` 生成至少 32 字节的随机 Token，使用 URL 安全 Base64。Redis 只存 SHA-256(token) 作为 key 后缀；不同用途分别使用前缀 `fg:auth:admin:<digest>`、`fg:auth:caller:<digest>`。
- Redis value 保存最小 JSON：`principalId, principalType, role, scopes`，由 TTL 控制寿命。管理会话 30 分钟，Caller 1 小时，首版不滑动续期、不自动刷新。前端展示到期时间，但仅保存在 JS 内存，不落 localStorage/sessionStorage/cookie。
- Caller 每个主体同一时刻最多一个有效 Token；Redis 另设 `fg:auth:caller-index:<principalId>` → token digest，TTL 与 session 保持一致。创建/替换/撤销必须维护两类 Key 的一致性；**先核实 Redis 操作成功再向用户宣称签发/撤销成功**。可以采用一段最小 Lua 原子维护两 Key，脚本需中文分点注释；拒绝为了此目的引入 Redisson。
- Redis 访问失败时，Gateway 保护路由拒绝（503/401 的区分须固定：Redis 故障 503、Token 不存在或过期 401、Scope 不足 403）。Control 的会话管理写入失败返回 503，不能悄悄签发不能验证的 Token。注销和吊销必须被正确测试。
- 管理员会话与 Caller 凭证不能混用；Gateway 仅消费 Caller key。向 Demo 转发前去掉客户端自带的 `X-User-Id`、`X-Principal-Id`、`X-Scopes`、`X-FlowGate-Internal-Key` 等可信身份/内部头；演示阶段不必主动向 Demo 注入用户身份，避免信任边界扩张。

## 6. 配置发布、不可变快照与回滚

### 6.1 快照 JSON 协议

```json
{
  "schemaVersion": 1,
  "version": 1,
  "checksum": "<sha256-hex>",
  "services": [],
  "apis": [],
  "routes": [],
  "policies": [],
  "bindings": []
}
```

`routes[]` 中至少包含 `routeId, method, publicPath, upstreamPath, upstreamUri, isPublic, requiredScope, priority, configVersion`。关联结构为白名单字段，不直接序列化数据库 Entity。**统一排序**（以 stable ID / routeId）并按固定字段顺序编码 JSON；计算 SHA-256 时移除最外层 `checksum`，其他字段不变。Gateway 使用相同规范计算并比对，不通过不同次序序列化导致误报。`schemaVersion` 只能为 1，版本严格单调增加。

### 6.2 发布事务步骤

1. Control 对当前所有启用的服务/API/路由和绑定做完整校验：合法路径、方法/路径冲突、Scope、白名单 URI、引用存在、策略参数类型。
2. 查询 `fg_config_state(id=1)`，要求 `expectedVersion` 与 `current_version` 一致；通过 `UPDATE ... WHERE current_version=? AND optimistic_version=?` 实施 CAS；影响行数不是 1 就返回 409。
3. **同一个 MySQL 事务**写入新 `fg_config_release`、更新配置状态、写 `fg_audit_log`。失败全部回滚，不能产生孤立已发布版本。返回 `PERSISTED`，**不能称 ACTIVE**。
4. 回滚先找历史快照、重新按当前白名单与 schema 验证，以历史有效载荷生成**更大的新版本**，`rollback_from` 为历史版本号；绝不把旧 version 数字写回 currentVersion。

草稿修改不影响 Gateway；更新数据库不自动改变已发布历史；只有新发布/回滚版本进入网关。

### 6.3 Gateway 无重启同步

1. 每 5 秒（可配置）用非阻塞 WebClient 请求 Control `/internal/snapshots/current`；首启版本 0 表示未配置任何动态路由。连接失败、HTTP 非 2xx、schema 错、SHA 错、非法 URI、非递增版本一律保留最后有效快照。
2. 使用不可变对象 + `AtomicReference` 保存已校验规则，动态 `RouteDefinitionLocator` 提供 Gateway 官方路由定义；触发官方路由刷新事件。**必须核对 Gateway 5.0.4 真实缓存更新完成时机**，路由匹配与鉴权使用同一条已匹配 Route 的 metadata，而不是实时读取另一版本的全局 Map。Codex 要为 V1→V2 并发切换写小规模定向测试。
3. **只有证实实际可用路由集已应用**才调用 `/internal/nodes/applied`；不能仅在收到 HTTP 快照或发出刷新事件后就报告 `applied`。优先使用 Gateway 官方刷新成功事件/完成反馈，具体 API 需以实际依赖验证；若暂不能确定，宁可将节点标为 PENDING，不得虚报成功。
4. 禁止在 Block 1 保留原块 0 的 `/api/demo/**` 广泛静态旁路路由，否则它可能绕过新版本鉴权。迁移后将 Demo 三个 API 注册为受控的已发布规则；发布前未知 API 404，私有 API 默认拒绝。启动/README 明确写出本地演示配置步骤。
5. 网关实例重启后必须先拉到可信快照才服务动态业务路由；Control 断联时已在内存的有效快照继续使用，**重新启动而无可信快照时不得擅自重新开启公共静态代理**。不在本块额外实现磁盘缓存或复杂多节点协议。

## 7. 前端测试台（本块必须同步交付）

> 前端不是产品管理后台，是**本地功能实验面板**。全阶段固定同步约束详见 `docs/standards/frontend-sync-rule.md`。此节是 Block 1 的具体验收合同。

使用 `flowgate-control/src/main/resources/static/test-console/{index.html,style.css,app.js}`，访问 `http://127.0.0.1:8081/test-console/`；禁止 npm/Vite/Vue/React。保持简单的中文表单、按钮、响应原文、HTTP 状态、请求耗时和错误提示。使用 `fetch`，不得在页面预置密码、Token、内部 Key。

测试台固定五个区域：

| 页面区域 | 功能块 0 已有能力/本块新增能力 | 最低操作 |
|---|---|---|
| A. 服务状态 | 既有 Gateway 与 Demo + 新 Control | health 探测及可读状态，不把服务无法访问误报为在线 |
| B. 基础 HTTP 测试 | Block 0 的 fast/echo/slow/Trace/状态码 | GET fast（id、tag）、POST echo（JSON、X-Demo-Header）、GET slow（delayMs）、显示 Request ID 与状态/响应 |
| C. 管理员与身份 | Block 1 | 管理员登录/退出、当前角色；Admin 创建/吊销 Caller；Caller Token 只保存在页面内存 |
| D. 服务/API/路由与发布 | Block 1 | 简单增改查表单（可文本 JSON 编辑）→发布 expectedVersion→查看 current/list/node→回滚任意历史版本 |
| E. 策略、审计和执行情况 | Block 1 | 策略草稿/绑定、审计表；明确提示“限流/熔断执行尚未完成”，未实现功能禁用而非伪造结果 |

- 首次打开提示操作顺序：**配置依赖 → 登录 → 创建/检查三个 Demo 公共 API（或执行明确的安全演示初始化步骤）→ 发布 → 查看节点生效 → 运行 Block 0 Demo → 新建私有 API/Caller 做 401/403/200 验证 → 变更/回滚**。
- 对 Block 0 的三个接口沿用路径与语义：`GET /api/demo/fast/{id}`、`POST /api/demo/echo`、`GET /api/demo/slow`。不重写 Demo 逻辑；测试台须能看到它们在 Block 1 动态发布后的运行效果。
- 前端所有按钮必须真实调用本地服务；未就绪/未实现时标“未启用”，不能展示固定的“成功”示例冒充结果。功能清单每块同步维护，标记“已实现 / 待开发 / 依赖未就绪”。
- 普通管理 AJAX 走 Control 同源；Gateway 访问必须按第 3 节配置固定 Origin CORS；不把内部 `/internal/**` 暴露给浏览器。
- 页面应兼容开发者工具网络错误、HTTP 401/403/409/503；展示原始返回但**不得把 Token、密码、内部 Key 写进日志或持久化**。Token 输入采用 `type=password` 或遮罩，退出时清空；网页刷新后重新登录属预期。
- 手工测试用例与前端版本状态表写入本文件进度节；每完成一块后续功能必须先同步页面再宣称本块完成。

## 8. Codex 实施顺序（按序完成，不另做方案）

**步骤 1：环境/分支**。读取 README、本文件和前端规范；检查 `git status -sb`、`git remote -v`、`git log -1 --oneline`。有未提交用户内容时不得覆盖。先执行 `git fetch origin`，检出并跟踪远端已存在的 `origin/feat/block1-control-publish`（例如 `git switch -c feat/block1-control-publish --track origin/feat/block1-control-publish`；如已有本地分支，先确认其提交历史再切换），**不要重新从 main 建立同名分支**；若远端获取失败，检查本地是否已有包含任务文档的同名分支，记录阻塞，不强行覆盖用户修改；若推送返回 403，保留本地提交和 SHA，不自行改动凭据。Block 0 已位于 main 的历史提交不得擅自改写。

**步骤 2：Control + SQL**。父 POM 加 control；建立本地环境配置示例和 MySQL DDL，实体/Mapper、基础权限模型；确保不会新增默认凭据；先只在 Control 进行 JDBC 与同步 Redis。

**步骤 3：身份管理**。BCrypt 管理员登录/logout + Admin/Operator/Viewer 权限；Caller 签发/撤销与 Redis digest Key/TTL；先做本地定向测试。

**步骤 4：目录与草稿**。完成服务/API/路由/策略的 CRUD、冲突与 URI 校验；不做执行型限流。

**步骤 5：发布/回滚与内部 API**。MySQL 事务 CAS、不可变快照、SHA、审计；内部 API 使用环境密钥并绑定回环；版本/校验错误有定向测试。

**步骤 6：Gateway 切换动态路由与 Caller Auth**。删去公开静态通配路由；接入响应式拉取、路由刷新、Route metadata 鉴权、定向 CORS；保留 Block 0 Request ID/网关指标；验证新旧版本行为以及 Redis 错误时的 fail-closed。

**步骤 7：前端测试台**。实现五区页面及实际 fetch；同步展示三种既有 Demo 操作，提供演示配置清晰指引；不引入前端工具链。

**步骤 8：定向整合/进度记录**。运行本块定向测试和最小三进程演示；维护 README 状态、两个阶段文档及测试台功能标记，按 Git 规范提交并停在本块，不启动块 2。

遇到版本 API 不兼容：先检查已锁定依赖的实际 API，在原技术栈内做最小调整，记录事实和偏差；**不把开放性架构选型交给 Codex**。

## 9. 本块定向测试（自动执行，不要求负责人此时人工测试）

必须记录**真实命令、通过/失败数、失败原因**。不得将“预期通过”写成“已通过”。

1. `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test` 与同范围 `package`；若 control / contract 未按需建立，按真实模块修正命令。保留 Block 0 既有相关接口的最小冒烟。
2. Control MVC：登录正确/错误、会话失效、退出、VIEWER 只读、OPERATOR 可发布、ADMIN Caller 管理；未登录 401、无权限 403；不输出凭证。
3. 目录：服务 URI 白名单合法/非法；GET/POST/PUT；方法/路径冲突、未知关联目标、非法 `{variable}` 或通配；策略参数/作用域非法返回 400。
4. 发布：V0→V1；重复 expectedVersion 返回 409；竞争发布只允许一个事务成功；发布失败无孤立版本；历史版本不可更改；V2→对 V1 rollback 生成 V3；审计真实写入。
5. Gateway：没有可信快照时不提供任意静态 API；发布 V1 生效，V2 新路由可调用；V3 回滚恢复；一个请求匹配的路由与其鉴权 metadata 来自同一版本；网关不重启。
6. Caller：公开 API 无 Token 能通过；私有 API 无 Token 401、错 Scope 403、有效 Scope 200；吊销后拒绝，TTL 到期拒绝；Redis 断连下私有 API 503 且下游未收到请求。验证伪造身份 Header 被去除。
7. 故障：快照错误 checksum/schema/非法 URI 不替换旧版本；Control 临时断联旧版仍工作；节点不在未实际加载时报告成功；不测多节点大规模故障。
8. 页面：`/test-console/` 可打开；页面按钮正确调用网关 Demo 和后台 API；显示实际结果/错误；没有外部 CDN、构建工具依赖、硬编码机密；Gateway 定向 CORS 正确，其他 Origin 不被授权。
9. 回归最低限度：Block 0 三个 API 在演示配置正式发布后仍满足 GET 200、Echo 201、slow 上下界、requestId 一致及 Actuator 的原有行为。`git diff --check` 和依赖树检查 Gateway 无 MVC/JDBC/阻塞 Redis。

本块不执行全量回归、统一压测、跨多节点长期故障、Kafka、Prometheus 完整展示；这些保留最终验收。

## 10. 负责人第一次手动 Demo 验收入口（仅本块做完再执行）

开发交付时 Codex 必须提供**实际可用**的环境变量示例、SQL 初始化命令、三应用启动命令、测试台 URL、演示数据初始化步骤及最少如下用户操作链；若运行环境不具备 MySQL/Redis，要诚实写“阻塞，未就绪”，不可冒充手动 Demo 已可运行。

1. 打开 `http://127.0.0.1:8081/test-console/`，看 Gateway/Control/Demo 三服务状态和未发布提示。
2. 管理员登录，创建或检查受控 Demo 服务与 fast/echo/slow 三条 API；点击发布 V1，页面显示 `PERSISTED` 和节点实际 `appliedVersion`，两者不是同一概念。
3. 使用测试台运行 fast、echo、slow，显示对应 HTTP 状态、业务 JSON、`X-Request-Id`；修改 slow 上限外参数看到 400。
4. 新增 `GET /api/private/fast/{id}` 同服务私有 API（scope `demo:read`）并发布 V2；无 Caller Token→401，错 Scope→403，正确 Scope→200。
5. 对 V1 执行 rollback，生成 V3；确认私有路由不再生效，原三个公共 Demo 路由恢复；审计能看到发布/回滚历史。
6. 停止 Control，检查已成功加载的 Gateway 仍按最近有效快照转发；重新启动 Control 并观察恢复。不以网页探测成功代替真实网络响应。

**本节为未来验收步骤，不表示已执行。**

## 11. 中文注释、Git 与交付约束

- 关键业务类、核心方法、异常边界、发布 CAS、Redis Token、WebFlux 路由切换均有明确中文注释；方法或逻辑块较长时必须使用 `// 1. ...`、`// 2. ...`、`// 3. ...` 分段解释**业务阶段及理由**。无需对 getter/setter 逐行注释。
- 流程正确性优先于抽象美观。新增依赖必须在交付记录说明其业务必要性；不增复杂模块、框架或与本块无关的构建链。
- 建议 Conventional Commits：`feat(control): ...`、`feat(gateway): ...`、`feat(console): ...`、`test(block1): ...`、`docs(block1): ...`、`fix(...): ...`。提交前 `git diff --check`，查看 staged diff 防止敏感信息。
- 只在 `feat/block1-control-publish` 本地/远端分支提交（如果有推送权限）；**禁止直接在 main 开发或执行 merge/rebase --force/push --force**。如果没有仓库权限，保留本地 commit SHA 并报告，不因 push 失败宣称整块完全交付。
- 不把测试报告、Commit、PR 与实际功能成功混淆。Codex 最后一条回复需写：模块清单、依赖变化、实现对照、前端状态、运行命令及真实结果、提交 SHA、阻塞、已知缺陷，等待负责人审核；不执行块 2。

## 12. Codex 原地回填（请仅填真实情况）

| 项目 | 当前状态／Codex 完工后更新 |
|---|---|
| 负责人要求 | Block 1 首版简易前端；Block 0 功能继续可测；手动测试延至最小可执行 Demo |
| 设计文档 | V2.0 已完成 |
| Block 0 关联 | 已有代码与定向记录；负责人尚未手动验收 |
| Block 1 分支及 SHA | 远端任务分支 `feat/block1-control-publish` 已创建；功能代码未开始，实施 SHA 待 Codex 回填 |
| Control 与 SQL | 未开始 |
| Admin/Caller 身份认证 | 未开始 |
| API/路由/策略管理 | 未开始 |
| 发布/CAS/回滚/审计 | 未开始 |
| Gateway 动态路由与非阻塞鉴权 | 未开始 |
| 简易前端五区（含块 0） | 未开始 |
| 定向测试命令与结果 | 未执行 |
| 实际三服务最小 Demo | 未完成 |
| 负责人第一次手动验收 | 未执行 |
| 源码审核、已知风险与差异 | 待 Codex 真实记录 |

**停止条件**：完成并记录本块后等待 ChatGPT/负责人审查；在没有完成最小可运行环境或真实测试前，状态只能是“部分完成/存在阻塞”，不可写“已验收”。