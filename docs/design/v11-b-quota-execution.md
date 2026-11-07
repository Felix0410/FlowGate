# FlowGate V11-B｜可信计量准入与月度配额账本：可编码详细设计

> **版本**：2026-11-07，设计合同；状态：**已设计，未编码/未实测B**。
> **明确接受负责人本轮调整**：V11-A静态复审的两项P0（同月非重叠套餐切换、MySQL连接UTC会话）**不再要求先单独修A分支**，而成为V11-B实施的**B0必做阻断任务**。只有B0独立实测通过后才允许编码B1～B6；A现有17 JUnit/155 HTTP保留为历史证据，不代表B0已修。
> 设计依据：`docs/design/openapi-platform-v1.1.md`第12节、`docs/reviews/v11-design-review-2026-11-06.md`、`docs/reviews/v11-a-static-review-2026-11-07.md`、`docs/progress/v11-b-quota.md`。
> 基线：`feat/v11-a-business`（完整A实装+审核）→ 当前B分支 `feat/v11-b-quota`。所有旧Draft PR与main不动。Java17/Boot4、MVC/MyBatis+MySQL8.4、Gateway WebFlux、Redis7、原生产品ES modules；**不加Kafka、不引新微服务/新前端栈**。

## 1. V11-B只解决什么业务问题

运营人员将受保护API声明为**计量API**，为Developer→Application配置套餐/订阅，然后安全签发专属App凭证。调用一个计量API时：

1. Gateway从**已发布、同版本快照**匹配Route和USAGE_QUOTA策略，验证**专用App Token**与Scope；
2. 原有Redis Lua速率准入、本机并发保护照旧；对已打开的熔断器尽量在计量前拒绝；
3. Gateway向Control可信内部quota端点发**一次有界非阻塞HTTP预准入**，Control用MySQL权威事务授予并扣减当月额度；
4. 成功提交Balance+Ledger+Outbox才准许进入下游Demo，失败/超额/依赖故障**不进入下游**；成功准入后哪怕下游500、超时、取消、Gateway崩溃，**不自动退款**；
5. 管理后台显示**准入单位、剩余、权威流水**。本块不做Kafka消费/异步统计，不产生收入/收费数据，也不把额度说成成功响应次数。

**V11-B新增的B0修复也是业务必要条件**：同App同UTC月只能选一次套餐；所有业务数据库连接真实UTC。否则进入B后额度冻结含义不确定。

## 2. 严格边界与不做事项

- **唯一权威源是MySQL**：每次计量成功授予由`fg_quota_balance`、`fg_quota_ledger`和`fg_outbox_event`同一个MySQL事务保证；Redis的`RATE_LIMIT`只做瞬时速率，不能推导月度剩余额度。
- 未显式绑定USAGE_QUOTA的旧路由**不增加内部quota HTTP**、不受Control额度依赖影响；旧普通Caller/管理员、V1发布/回滚/熔断行为不被悄悄改变。
- 新应用Token**只在私有计量路由**可用；旧普通Caller不能消费新计量API额度。旧Gateway即便继续运行也绝不认可应用Token。运行同一个旧快照的普通Caller历史流量属于旧兼容流量，不应冒充V11计量业务；启用计量前必须确认并隔离所有可接计量流量的旧节点。
- 此块不做Kafka Broker、生产者/消费者、账单/付款、Redis额度预分配、分布式事务/Seata、持久化请求正文、按下游2xx才扣费、通用动态身份系统。
- 人工真人浏览器验收及V1主线整理仍是最终交付要求，不能以Node模拟DOM代替。B仅交付定向Java/Node/HTTP、MySQL并发与跨节点证据；正式四组长压测留D。

## 3. B0：把A的两个P0修复正式并入B并先阻断

### B0.1 同UTC月份一次套餐选择

实际源码`SubscriptionSchedule.validate`只拒绝两个`[start,end)`重叠区间。补规则：

- 对任意同App已有有效管理订阅（包括已时间到期但行状态仍为ACTIVE），如果其订阅区间**与目标订阅起始所在UTC自然月**有交集，就不得再创建该月的新订阅。已有`[2026-11-01,2026-11-10)`，此时`now=2026-11-20`，试图建`[2026-11-11,2026-12-01)`，必须409 `SUBSCRIPTION_CONFLICT`；不是因为直接重叠，而是因为同月套餐已锁定。
- `[2026-11-01,2026-12-01)` + `[2026-12-01,2027-01-01)`是允许的相邻期间，下月起点必须UTC月首日零点；当前月首次创建允许从当月第一天起、结束必须合理。**本块再补一个范围限制：首版每条套餐订阅只能落在一个UTC计费月内**（`effectiveAt >= monthStart`且`expiresAt <= nextMonthStart`），避免跨三个月占用后造成未来计划歧义；若有合法历史跨月订阅数据，必须迁移前先报告，不可静默截断。
- 订阅状态`ACTIVE/SUSPENDED`与派生`CURRENT/SCHEDULED/EXPIRED`不能混用；即使行状态不ACTIVE，也不允许重写已授权过的当月历史。此处对未来尚未生效且被明确取消的订阅的重新排期，首版**不提供删除/取消API**，避免兜底绕过。
- 真实MySQL事务继续沿用A的应用行`FOR UPDATE`与当前读；验证同月已过期反例、月末毫秒、下月相接、双管理员同时写一201一409。

### B0.2 MySQL数据库连接会话统一UTC

- 当前`application.yml`的`connectionTimeZone=UTC`只声明JDBC转换，不自动设置`@@session.time_zone`。首选对Hikari设`connection-init-sql: SET time_zone = '+00:00'`，保留JDBC UTC映射并校验与`FG_MYSQL_URL`覆盖兼容；若实测需`forceConnectionTimeZoneToSession=true`，只能采用一种明确可靠方案，不能把两种矛盾写在一起。
- 业务时钟`ApplicationEntryMapper.nowUtc()`应使用真实UTC时间源并验证MyBatis映射到Java`Instant`；确认MySQL TIMESTAMP(3)读写不被JVM默认时区二次偏移。
- 必须在隔离MySQL`@@global.time_zone`非UTC、JVM`Asia/Shanghai`与另一个JVM时区至少两组配置下，比较`@@session.time_zone`、`UTC_TIMESTAMP(3)`、`CURRENT_TIMESTAMP(3)`、TIMESTAMP回读，并覆盖11月末最后一毫秒到12月1日。已有V1`fg_release_node.last_seen_at`/审计/快照读取需回归，不能只修订阅却弄坏旧节点在线判断。
- **B0独立提交+独立定向/真MySQL证据**；未通过则**停止**，不开始发App Token或扣量。本节为用户明确授权的阶段迁移，旧A复审报告保留历史原始结论。

## 4. 协议、节点能力与发布门槛（B1）

### 4.1 新策略和唯一归属

- 继续使用原`fg_policy`和`fg_policy_binding`，新增类型`USAGE_QUOTA`，作用域**仅API或ROUTE**，参数固定JSON `{"unitsPerCall":1}`（允许1..100整数，最多1条生效策略/实际Route）。
- 只绑定`enabled`且`isPublic=false`的API/Route；必须有明确Route引用的单个API，不能通过路径歧义把A应用的授权记到B的账本。发布前检查某Route不得同时被API、ROUTE两条Quota匹配。
- `PolicyRules`引入独立`ENFORCED_QUOTA_V1`执行标识；`ReleaseService.build/rollback`和历史快照`schemaVersion=1`保持旧字段与checksum规则；不修改历史JSON、不改变旧策略执行标记。
- **严禁**直接把USAGE_QUOTA加入`EffectivePolicyResolver.enforced`并按旧`capacity/refillPerSecond`字段构造Rule——当前源码对非并发类型取这两个字段，会产生NPE。新增独立`QuotaPolicyResolver/QuotaPlan`，从同版Snapshot冻结`routeId,apiId,unitsPerCall,policyId,appliedVersion,checksum`到Route metadata；无Quota为`NONE`。现有Resilience/RATE/CONCURRENCY解析器不处理Quota，保障旧路线。
- 回滚到旧版本以后，Route metadata无Quota，因此不扣余额、App Token不再被普通Caller通道接受；`SnapshotCodec.verify`/RouteSpec原记录仍兼容。

### 4.2 可接流网关能力门槛（不能只相信节点ONLINE）

- 给本地演示明确`FLOWGATE_QUOTA_TRAFFIC_NODES`白名单，例如`gw-a,gw-b`。**默认空=拒绝启用计量策略**，不默认为某个随意上报的nodeId；部署者需确认它就是可接受业务流量的完整节点集合。
- 新Gateway在已有`POST /internal/nodes/applied`上报附加`supportedFeatures:["USAGE_QUOTA_V1"]`，旧请求不含字段必须兼容且将该节点能力标为未知/不支持，不能沿用旧心跳遗留能力。可最小新增`fg_node_capability(node_id PK,features_json,version,checksum,last_seen_at)`，避免破坏原`fg_release_node`旧结构。旧节点心跳或过期/版本校验失败、白名单节点缺失或不支持Quota→发布含Quota的配置一律409 `GATEWAY_CAPABILITY_NOT_READY`。
- 新节点必须能先应用旧V1快照并上报能力，之后才发布新Quota；Control以受信internal key和明确接流节点表校验。不能仅用一个支持的节点+两个其他未上报节点就允许发布。**这只是本机部署证明**，真实线上仍需接入层下线/排空旧节点，不能因心跳声称自动隔离所有未知公网副本。
- 某旧节点仍握旧快照时，无法使用独立App Token；新APP业务不会从旧节点偷跑扣量。旧普通Caller旧业务行为在升级窗口可维持，但平台不能把它统计为已计量应用调用。
- 若发布/回滚历史Snapshot含USAGE_QUOTA，都须根据**最终将执行的目标Snapshot**进行能力门槛检查（回滚至含Quota历史仍需门禁；回滚到无Quota的旧版本不需要）。
- 证据：双新节点都支持时允许发布；单旧/单新节点或缺失心跳时拒绝；旧Token和旧快照/校验和维持；节点修改/重启重报没有错误继承。

## 5. 应用身份专用通道与凭证生命周期（B2）

### 5.1 与V1 Caller两层隔离，尤其在混合Gateway版本下

- 保留旧V1`fg:auth:caller:{digest}`和43字符不带前缀Opaque Token；旧Gateway`CallerAuthFilter`只认识此命名空间和43字符。
- **V11新App Token**格式约定`fgapp_` + 安全随机32字节Base64URL无padding（43字符），总长度49；摘要只写`fg:auth:app:{digest(fullToken)}`和`fg:auth:app-index:{principalId}`。旧Gateway连格式校验都无法通过；更不会查询新命名空间。
- `fg_principal`新增行用途`principal_type=APP_CALLER`、`role=APP_CALLER`（现有列能容纳，不增旧表字段），只经`fg_app_principal`关联一个Application；**不得用旧`IdentityService.createCaller()`签发新App Token**，旧`/admin/callers/{id}/revoke`只处理普通CALLER。新`AppCredentialSession`或等价专用DTO含`principalId,APP_CALLER,scopes,credentialGeneration`，由新Gateway专用解析，不修改旧Session的四字段含义。
- 当前`fg_app_principal`没有持久凭证版本字段。**B新建**`fg_app_credential_state(principal_id PK FK, generation BIGINT, enabled BOOLEAN,updated_at)`，不对A已有表做有损ALTER。每App首版只允许一条有效principal，首次创建/轮换/吊销都先锁`fg_application`行再查映射并执行generation单调变更；`fg_app_principal`已有`application_id`索引虽非唯一，但服务事务和唯一principal记录保证本版不会创建第二个有效关联，测试并发双发证。
- 实际签发顺序**明确不假装MySQL+Redis事务**：Control在MySQL提交主体/映射/generation变更后，才将新Token通过Redis Lua原子替换同principal旧digest并设置TTL；仅Redis成功ACK后一次性返回明文Token。如果Redis失败，当前generation已经更新、旧Token在MySQL版次检查时失效；用户可重试签发，可能短暂没有可用Token，**不允许因失败继续认可旧Token**。令牌明文只存在本次请求内存，不保存DB/审计/日志/前端本地存储。
- 轮换generation+1；吊销`enabled=false`、generation+1并尝试删Redis；即使Redis删除失败，Quota Control每次事务仍核对generation+enabled，不认可旧凭证。暂停App/Developer同样在DB权威状态拒绝；不能依赖Redis立即清理实现安全。
- `POST /admin/apps/{id}/credentials`从A的409占位改成ADMIN-only真正签发，新增明确轮换/吊销API如`POST /admin/apps/{id}/credentials/rotate`、`POST /admin/apps/{id}/credentials/revoke`；服务端只返回一次Token，GET列表仅返回`ACTIVE/REVOKED/NOT_ISSUED`等安全摘要，不返回密钥/Hash/Redis摘要索引。
- App Scope由管理员配置并存Principal.scopes_json，入口Redis会话可提前403，但**最终Quota SQL再次核对Scope/Principal enabled/APP所属**。旧Caller凭证访问显式Metered route必须拒绝而非绕开扣额；App Token访问非计量私有API必须拒绝，避免把普通调用方鉴权和月度计量混用。旧公开API不因APP Token前缀额外受保护。

### 5.2 认证细节与状态机

- `CallerAuthFilter`依Route同版Quota metadata分支：非计量private走旧Caller路径；计量private只读APP前缀/新Redis namespace并将`principalId,generation`放**服务器内部exchange attributes**，清除向Demo的Authorization header；不用客户端自报`X-App-Id`。所有Session类型/Scope无效或Redis异常都明确401/403/503并在下游调用之前返回。
- 撤销/轮换后仍处于途中的已获quota Grant可按“授权授予即消耗”解释；后续再次quota检查因DB生成代次不符而拒绝。无新数据库-Redis分布式锁、不读取明文Token。

## 6. 权威额度数据与DB事务（B3）

### 6.1 增量SQL（不删除V1/A的任何数据）

在`sql/flowgate-v11.sql`里仅追加`CREATE TABLE IF NOT EXISTS`：
1. `fg_quota_balance`：`id`、`application_id`、`period_yyyymm CHAR(6)`、`subscription_id`、`plan_id`、`plan_version`、`total_units BIGINT`、`remaining_units BIGINT`、`version`、`created_at,updated_at`；`UNIQUE(application_id,period_yyyymm)`、`CHECK remaining>=0,total>=0,remaining<=total`及FK。
2. `fg_quota_ledger`：`id`、`charge_id CHAR(36) UNIQUE`、`request_fingerprint CHAR(64)`、`application_id,principal_id,credential_generation,api_id,route_id VARCHAR(200),applied_version,policy_id,period_yyyymm,units,created_at`；保证消费事实可查、可与配置版本核对。
3. `fg_outbox_event`：`id,event_id UNIQUE,event_type,payload_json,status=NEW,next_attempt_at,attempt_count,lease_owner,lease_until,last_error_code,created_at`。B只写`USAGE_GRANTED`（不连接Kafka）；C才领取/投递/消费。内容只有必要app/API/单位/UTC时间/eventId/chargeId，不包含Token/Body/Header/IP。
4. `fg_app_credential_state`如上；`fg_node_capability`如上。**B合计新增5张表**。依真实SQL与查询需要加最少索引；对已有A表不使用可能破坏数据的ALTER。对于`fg_quota_balance`B测试只在隔离库加事实，不给用户原有数据库灌演示记录。

### 6.2 计量必须是一个原子事务

内部`POST /internal/quota/charge`请求由可信Gateway发：`{chargeId,principalId,credentialGeneration,apiId,routeId,appliedVersion,snapshotChecksum,units}`。AppId不能由客户端传入、Control从`fg_app_principal`反查。响应：
- 200 `{chargeId,chargedUnits,periodUtc,remainingUnits,duplicate:false}`；
- 幂等重试200 `{...原已授予归属,duplicate:true}`（需要重新确认当前应用/凭证未被禁用；如果现在已被禁用应403，不再代理，账本仍保留）；
- 403 `APP_DISABLED|DEVELOPER_DISABLED|NO_ACTIVE_SUBSCRIPTION|INSUFFICIENT_SCOPE|APP_TOKEN_REVOKED`；
- 429 `QUOTA_EXHAUSTED`；
- 409 `IDEMPOTENCY_CONFLICT|CONFIG_VERSION_MISMATCH`；
- 内部鉴权失败401，Control/MySQL不可达/总耗时超限503 `QUOTA_UNAVAILABLE`（Gateway对异常统一503，不将未知结果当授权）。
- 所有错误继承`X-Request-Id`及原项目统一JSON错误体，严禁回显另一个Application余额。

**建议具体事务算法（实现要与测试一致）**：
1. 检查Internal Key和请求参数/UUID长度、固定上限，查询可信历史`fg_config_release[appliedVersion]`与checksum，校验启用/私有/相同route+API+policy+units，不能用Control最新配置替代；不存在/不匹配拒绝，不扣量。
2. MySQL事务中获得Application共享锁并检查Developer、Principal`APP_CALLER`、`fg_app_principal`、credentialGeneration+enabled、当前有效订阅/冻结Plan版本、Scope。与A客户/应用修改的独占锁配合，保证状态转换不会在判定过程中偷偷穿透；锁顺序统一`app→developer→plan/subscription→balance→ledger/outbox`避免循环锁。
3. **先按`chargeId`查历史Ledger**：如果有，按规范化不可变参数计算/比对fingerprint（包括可信appId/principalId、generation、apiId、routeId、policyId、units、appliedVersion、checksum，但**不包含当前UTC月**）。匹配且当前凭证/应用仍允许才返回旧grant，`periodUtc`取**原Ledger中被冻结的月份**；不同内容409；同chargeId跨UTC月不产生第二月扣减。若grant后应用被停用，历史仍存在但这次重试403，不借历史成功绕过停用。
4. 首次Grant从数据库UTC clock计算当前`YYYYMM`，确认一条当月有效订阅；若Balance不存在，插入唯一`(app,period)`冻结`subscriptionId+planId+planVersion+totalUnits`。并发首次初始化因唯一键竞争必须可恢复，不能创造两个余额行。
5. 在**同一事务**执行Ledger唯一`charge_id`插入（也起幂等竞争序列化作用），再`UPDATE fg_quota_balance SET remaining_units=remaining_units-:units,version=version+1 WHERE ... AND remaining_units>=:units`（更新行数必须为1），最后插入Outbox事件；全部提交才返回200。更新0行→显式429并回滚未完成的Ledger/Outbox；别捕获SQL异常并在已rollback-only事务内继续写。
6. **并发重复chargeId处理**：另一事务可能先通过初始SELECT，最终在UNIQUE insert等待后遇重复；必须整体回滚该尝试，再开独立新的已提交读取来比较fingerprint与原Grant并返回duplicate，而不是让不同的请求各扣一次。第一次执行先声明已插入Ledger而未扣减也只是在未提交事务内可见，事务回滚不会留下悬空Ledger。
7. 没有quota grant的401/403/429/503**不能产生Ledger/Outbox记录**；被Redis RATE_LIMIT或本机CONCURRENCY拒绝不得请求Control；成功grant后下游5xx/超时/cancel不退款。

**数据库与缓存**：授权/剩余量始终读MySQL。已有Redis用于限速/Token，B不必为了“展示一致性”强加Redis余额缓存。若以后确需对套餐展示使用只读Cache Aside，先证明读压收益，写DB提交后删缓存、短TTL与失败补偿可作为扩展；绝不把缓存旧值当成能否授权的依据。

## 7. Gateway过滤链与失败语义（B4）

### 7.1 必须实际确认的组合顺序

当前源码：`CallerAuthFilter.getOrder=-100`、`AdmissionGlobalFilter.getOrder=-90`；Route的`CircuitBreaker`通过GatewayFilterFactory在Route filters中，现有文档称其执行顺序已验证，但它与全局过滤器会被合并排序。**不能把Quota放在Admission -90里就声称熔断OPEN不扣额**。

推荐新增`QuotaAdmissionGlobalFilter`且仅计量Route生效，`getOrder=+10`（**只是待实测的设计值，不是框架保证**）：意图让CB路由过滤器(通常order0)短路后不进入quota，而又在NettyRoutingFilter之前调用Control；原`AdmissionGlobalFilter -90`持有LocalConcurrency Lease直到内层quota和下游完成。需要实际输出组合Filter排序与注入CB OPEN场景确认：
- 已确定OPEN的CB应短路在Quota之前：未扣额、未产生Ledger。若经实测无法实现且改顺序会破坏V1，**B阻断此类计量+熔断混合路由发布**，或者重新把“授予额度即消费、即使CB后来OPEN仍可能扣”作为明确产品合同经负责人批准，不能偷偷承诺“熔断先拒绝”。
- 无CB路由由Quota直接在代理前运行，不增无配额路由内部HTTP；原TIMEOUT/CONCURRENCY/Rate过滤层效果不回归。
- `Mono.defer`+超时/有界连接池（可先使用Spring WebClient默认+显式信号限额）+生成一个服务器UUID chargeId，同一次quota内部通信失败最多有限次重试**相同chargeId**；绝不重试已经发到下游的POST/PUT。不调用`block()`/JDBC/同步Redis。
- 若内部HTTP已提交但响应丢失，重试同chargeId返回历史Grant；仍未知→503 QUOTA_UNAVAILABLE。实际已扣值在Ledger保留（UI告知授权已消费）。
- 网关不接受`X-App-Id`、`X-Charge-Id`或客户端请求体作为可信授权参数；仅交换内部attributes的`principalId,generation`和同版本Route metadata。

### 7.2 可观测性与错误体

- 保留原V1`RATE_LIMITED 429`、`CONCURRENCY_LIMITED 503`、`CIRCUIT_OPEN 503`、`UPSTREAM_TIMEOUT 504`、`DEGRADED 503`的含义；
- 新增`QUOTA_EXHAUSTED 429`、`QUOTA_UNAVAILABLE 503`、`APP_DISABLED/NO_ACTIVE_SUBSCRIPTION 403`、`APP_TOKEN_NOT_ALLOWED 401/403`等；每个有精确中文说明和`requestId`。
- 记录低基数指标（策略类型、结果码、路由规范ID），不要把appId、chargeId作为常规Micrometer tags；管理分页可查Ledger/余额。需要区分quota hop latency与端到端代理时间。

## 8. 内部API、管理端API和前端页面（B5）

- 内部Quota端点`POST /internal/quota/charge`仅原`X-FlowGate-Internal-Key`加已配置网络隔离，**绝不能由动态Route或公开控制台转发到`/internal/**`**；管理前端永远不携带该密钥。防止客户端伪造内部Body，GET/任意HTTP不允许。
- ADMIN应用凭证POST签发/轮换/吊销；OPERATOR/VIEWER只读App/Balance/Ledger，原V1 OPERATOR旧业务操作不变。新增`GET /admin/quota/balances?applicationId=&period=`、`GET /admin/quota/ledger?applicationId=&period=&afterId=&limit=20`，limit1..100，按权限安全DTO返回，不返回Token/Hash/内网IP/其他租户用量。
- `/console/`现有“应用管理”显示“未签发/已激活/已撤销”、一次性Token领取和危险操作确认；“套餐与配额”按应用显示当前UTC月份冻结套餐、已授予单位、剩余；“API/路由”表单可创建`USAGE_QUOTA`策略并在发布中心确认实际节点能力/版本；“用量明细”先展示权威Ledger，不把尚未开发的Kafka汇总画成已同步。
- 订阅表单完成B0后应支持“本月开始/下月首日”业务选项，不要求非开发者自己手填ISO8601；正式页面始终以数据库时间/响应为真。身份与Quota的所有新页面必须真实API驱动，不能用假余额/按钮替代。
- B缺Kafka时Outbox仅在MySQL NEW等待C，UI如展示必须明确“异步投递尚未启用”，不允许伪造“已发送/已对账”。

## 9. 测试矩阵与分块完成标准（B6）

| 组别 | 必做隔离实测 | 成功判据 |
|---|---|---|
| B0-A旧缺口 | 同月非重叠换套餐、MySQL非UTC时区/JVM时区/UTC月末、旧审计节点 | 该月第二次选Plan409；时钟与Instant精准；V1兼容 |
| 发布能力 | 旧Gateway+新Gateway混跑、未注册节点/缺失功能/过期心跳/回滚旧快照 | 不支持全套节点时新计量配置不能发布；旧APP token仍不被接受；无Quota回滚正常 |
| 应用凭证 | 签发/轮换/撤销、双ADMIN并发、Redis失败、停用App/Developer | 新旧命名空间完全隔离；旧版本或旧Token不再获授权；明文只一次 |
| 权威守恒 | 同应用两Gateway并发100次、套餐额度5、units=1 | 恰好5个不同grant，余额0，Ledger=5，Outbox=5，余95返回429且没有负余额 |
| 幂等跨月 | 同chargeId 100次并发及不同payload；模拟11/30 23:59→12/1 00:00 | 仅扣1次且只在原月留Ledger，重复返回同月历史；payload不一致409 |
| 链路失败 | Redis RATE/CONCURRENCY拒绝、CB已OPEN、Control/DB停机、WebClient丢响应、下游500/timeout/cancel | 拒绝前不扣；不确定503 fail-closed；已grant即消费，许可归还 |
| 旧业务 | 老Caller授权/撤销、旧配置版本/回滚、原限流/超时/熔断/fallback | 未计量路由完全按V1，响应与节点状态不回归 |
| 正式产品 | Java17定向+Node产品ES Module+真实双Gateway HTTP、375/1280人工反馈 | 中文表单/API权限/安全展示，脱敏证据、SHA/命令/退出码完整 |

**阶段完成不代表正式全量性能验收**：B只跑最小可复现并发/故障与短基准，正式DIRECT/PROXY/GOVERNED/METERED公平压测留D。若未经过实际MySQL/Redis+双Gateway验证，不允许勾选“B完成”。

## 10. 推荐实现文件与提交边界

| 步骤 | 优先改动文件/模块 | Git建议 |
|---|---|---|
| B0 | `SubscriptionSchedule.java`、`application.yml`、`ApplicationEntryMapper.java`、`V11BusinessTest.java`、`scripts/v11-a-smoke.py`增量用例 | `fix(v11-b): close month and timezone gaps` |
| B1 | `flowgate-contract/PolicyRules.java`，`ReleaseService`、`ReleaseController`，`SnapshotPollingService`和新QuotaPolicyResolver、节点能力表/映射 | `feat(v11-b): guard quota publishing with gateway capabilities` |
| B2 | `flowgate-contract`新增App专用Session DTO；`IdentityService`独立AppCredentialService、`CallerAuthFilter`双用途、安全管理Controller | `feat(v11-b): isolate app credentials from legacy callers` |
| B3 | `sql/flowgate-v11.sql`新增5表、Control Entity/Mapper、QuotaService、InternalQuotaController，历史ConfigRelease核验 | `feat(v11-b): implement transactional quota ledger and outbox` |
| B4 | `flowgate-gateway/admission/QuotaAdmissionGlobalFilter.java`、WebClient/Route metadata及组合Filter顺序测试 | `feat(v11-b): gate metered routes before upstream` |
| B5 | `static/console/openapi-business.js`、`governance.js`、`operations.js`、指南 | `feat(console): expose credential quota and ledger workflows` |
| B6 | 新`V11Quota*Test`、`scripts/v11-b-*.py/mjs`、脱敏证据、`docs/progress/v11-b-quota.md`真实进度 | `test(v11-b): verify concurrent grants and failure isolation` |

**实施契约见`docs/progress/v11-b-quota.md`新增B-07～B-15、Codex专用`CODEX_V11_B_EXEC_PROMPT.md`；B0必须先过后才写剩余业务。复杂Java/JS中文注释按`// 1.` `// 2.` `// 3.`，不创造与现有MyBatis/Gateway体系无关的架构。**
