# FlowGate V1.1 OpenAPI平台｜设计与V1源码一致性审查

> 2026-11-06。审查起始设计SHA：`7521b1496058228fe3a7e3d3ecff32f9e55f04f5`（`docs/v1.1-openapi-platform`），基线：`test/block4-final-acceptance`，设计PR #6。
> 资料：`docs/design/openapi-platform-v1.1.md`、V11-A/B/C/D与总路线、`CODEX_V11_OPENAPI_PROMPT.md`；实际源代码：`IdentityService`、`AdminAuthInterceptor`、`ReleaseService`、`CallerAuthFilter`、`AdmissionGlobalFilter`、`sql/flowgate-init.sql` 及现有 `/console/` 产品后台。
> **审查类型：设计+GitHub远端静态代码审核**，非实现测试。V11所有代码块仍**未开始**；没有运行V11 JUnit、Docker、真实浏览器。V1产品仍需负责人浏览器体验与最终收尾，PR #1–#6保持Draft、`main`不合并。

## 1. 总体结论

**附带P0安全约束后可进入V11-A开发，不建议同步开始B/C/D。** 将项目从“展示Gateway能力”转为“开放API运营+准入配额”的方向成立。架构保留V1原有路由与治理能力，新增客户/应用/套餐/订阅的管理域，未来在显式计量路由上经Control/MySQL授予额度，用同事务Ledger+Outbox与Kafka异步用量读模型。新技术的引入有明确业务必要性，避免将Redis令牌桶伪称月额度、将Kafka宣称权威扣费。

**业务边界名称必须统一**：Quota=**请求准入授权单位**，不代表成功处理/财务收费；一经数据库授权，后续失败/取消不退费（本版本不做退款）。旧V1无计量路由完整兼容，Client-side新发请求是新的消费尝试；网关到Control的同一次通信重试必须复用内部chargeId。

## 2. P0必须在编码前确定的设计约束

### R01｜新应用凭证与旧Gateway互通导致的授权绕过（P0，**必须修正原提案**）

现有`CallerAuthFilter`在`-100`顺序中从`fg:auth:caller:{digest}`读取`Session(principalType=CALLER, role=CALLER, scopes)`。如果B阶段通过旧`IdentityService.createCaller()`直接为应用签发相同格式/命名空间的活跃Token，**未升级的旧Gateway或未计量旧路由会把它当合法通用Caller**；仅在新网关代码中加`app-only`条件并不能防混合版本。

**固定解决方案**：
1. 应用专用凭证使用**独立摘要命名空间**`fg:auth:app:{digest}`与`fg:auth:app-index:{principalId}`；Redis Session标记独立的`APP_CALLER`用途，不能同时写`fg:auth:caller:`。内部`fg_principal`仍可复用CALLER行加`fg_app_principal`关系，但不得把它通过旧Caller登录/轮换路径激活。
2. 新Gateway只对**同版快照明确METERED、受保护的路由**读取app专用namespace并使用可信principalId；非计量私有路由拒绝App Token，旧Gateway无法读app命名空间→401。旧普通Caller仍按V1路由与权限工作，不允许被当作App绕过账本。
3. 应用停用、开发者停用、订阅失效的业务状态在配额MySQL事务重新查验，**不能只依赖Redis旧授权摘要**。如果应用凭证撤销Redis失败，计量端仍以数据库状态拒绝；应提供明确的补偿/状态，不能宣称Redis+MySQL原子。
4. A阶段严格不签发应用活跃凭证（`409 FEATURE_NOT_READY`），B阶段签发与app-only路由强校验**同时生效**，并用旧Gateway与新Gateway混跑测试证明不绕过。

### R02｜旧Gateway不识别USAGE_QUOTA时的绕过（P0）

现有`ReleaseService.build()`将非RATE_LIMIT/CONCURRENCY策略标记为`PROTECTED`，旧Gateway已有TIMEOUT/CB特定解释，但不识别新USAGE_QUOTA；如果在旧节点仍接流量时发布计量路由，**它可能直接代理、未扣月额度**。

**固定门槛**：V11-B增加网关`supportedFeatures`/能力版本的可信节点上报或等效本地受控兼容门槛；发布USAGE_QUOTA前必须确认**所有被计量流量可到达的Gateway**均运行支持配额的版本且已有能力/应用状态。仅凭`appliedVersion`和心跳ONLINE不足以判断实现能力。无法证明节点全部支持时**拒绝计量策略激活**（409/503），不静默放行。回滚到旧快照时不再计量；旧快照校验和不改。两Gateway混合升级专项测试是B阻断门槛。不能将Client看到的已发布版本误当集群具备功能。

### R03｜AdminAuthInterceptor写权限过宽（P0）

当前`AdminAuthInterceptor`对大多数`/admin/**`只挡VIEWER写，**OPERATOR可以写服务、策略和发布**，属于既有V1能力。V11-A设计明确首版新客户/应用/套餐/订阅只能ADMIN写，不可只在前端禁用按钮，必须增加**路径+HTTP Method明确的服务端授权规则**（`/admin/developers/**`, `/admin/apps/**`, `/admin/plans/**`, `/admin/subscriptions/**`，含凭证与轮换）。OPERATOR与VIEWER只读；旧V1路径原角色契约不变。不可把GET凭证类操作放行，新增读取端点只返回安全DTO。JUnit+真HTTP以ADMIN/OPERATOR/VIEWER/Caller各跑写/读矩阵。

### R04｜跨月幂等和历史快照二次核对（P0，B阶段实现）

- **重复chargeId先查历史Ledger并比对完整规范化不变业务参数**（appId/principalId/apiId/routeId/units/可信策略版本/原始授权期间），匹配则返回**旧grant结果**，不能在月初重新以新UTC月份扣第二次。第一次请求按**数据库统一UTC时钟**确定月份并冻结于Ledger；跨月重试优先走已有记录。
- 账本建立与余额条件扣减在**一笔MySQL事务**中，唯一`(application_id,period_yyyymm)`及`charge_id`；第一次余额初始化有竞争时依靠唯一键+行锁/条件UPDATE防超卖。同一chargeId不同payload返回409，不用事务异常吞掉竞争。若同chargeId重试时用户/应用后来被停用，**不能基于旧grant默默绕过当前禁用**；区分“查询历史结果”与“允许继续代理”，业务撤销检查在网关放行前可独立失败闭合，需固定契约与测试。
- Control必须用可信`appliedVersion`读取**对应不可变历史`ConfigRelease`**并重验route/api绑定、isPublic和units；不能只按Control最新版本判定，否则合法滞后网关错扣/误拒。若历史版本不存在或规则关系不符，拒绝并不扣款。
- 用户无法传入权威chargeId/appId：Gateway每个代理尝试生成，复用仅限Gateway→Control内部有限重试，严禁对已发给Demo的POST/PUT自动重试。
- 熔断与配额顺序依**实际组合Filter链**验证，不能靠“看起来先执行”解释。Gateway的route过滤器与全局过滤器会统一排序，需给出测试可证明已经OPEN的短路不扣账；如无法可靠满足则收窄B计量范围并明确获grant即扣的语义。

### R05｜订阅与套餐版本/月末更改要有不可歧义的日历规则（P0-A数据模型，B执行）

V11-A在UTC时间下建Developer/App/Plan/Subscription，但“当前订阅+下月排队订阅”还缺**何时选择、冲突如何拒绝、变更是否追溯**的规则：

1. effectiveAt/expiresAt一律UTC Instant，左闭右开`[start,end)`；查询“有效”要结合状态ACTIVE、当前时间和应用/开发者状态。一个应用任意时刻最多1个生效订阅，首版允许0或1个下月排队变更；新的排队版本要在月初**UTC第一天00:00**生效，其他日期转换/无效请求返回400或409。
2. 月度`totalUnits`冻结在`fg_quota_balance`第一次创建时，并记录所依据套餐版本/订阅身份（在B余额表添加如`subscription_id,plan_version`）；**不能在计划编辑后悄悄改已用月份额度**。
3. 如果A允许修改Plan的monthlyUnits，应采用**新version**或不可变修订，而不是覆盖同一条原值造成旧订阅历史含义变化。最小实现建议：首版Plan额度创建后只允许修改名称/启停，额度调整通过新Plan/新订阅实现，B侧冻结并测试。
4. **切换规则不强行自动给本月补额**；停用立即禁止新计量授权，但不删除已消费账本；过期后无有效订阅按403拒绝。A结束时必须能在隔离MySQL证明双管理员并发创建订阅不会重叠。

### R06｜安全与交付的阶段门槛（P0）

V1产品后台源码已在`test/block4-final-acceptance`提交，但负责人1280px/375px手动操作及最新V1全量验收尚待收口。V11设计分支不是main发布，不能把V1成果写为V11已实现。**允许准备V11-A代码的独立Draft PR，但不得跳过V1基线/数据保护核查，也不得提前签发应用Token或启动B/C生产行为。**

## 3. P1（按后续块实施，先写清楚）

### R07｜Outbox租约字段与恢复（C，P1）
B阶段`fg_outbox_event`初始DDL直接预留`lease_owner, lease_until, last_error_code`（以及必要时间/状态索引），免去C对同表无保护ALTER；C才启用Kafka。Worker在短事务中用`FOR UPDATE SKIP LOCKED`领取**仅Outbox类队列**、更新租约然后提交，**绝不持DB事务等待Kafka ack**。在MySQL官方语义中SKIP LOCKED返回非一致视图，适用于任务队列，不适用于额度授权判断。重投必须保证consumer eventId去重、写成功才提交offset。

### R08｜V11-D四组性能公平性（D，P1）
D METERED必须与A/B/C有**等价的HTTP方法、入口API、授权范围/身份条件、请求体和路由负载**；纯直连无法提供Gateway认证应如实单独标识非等价部分。D套餐余额用足够额度，不能因为quota耗尽使快速429污染成功P95；“业务成功RPS、全部HTTP完成率和准入拒绝率”分别报告。Gateway→Control的平均/P95 hop及MySQL锁等待是核心成本，不能只拿V1代理数据做结论。

### R09｜应用身份和权限范围（B，P1）
使用`APP_CALLER`单用途namespace后，原CALLER和App Token身份域隔离；App Token的Scope可从Redis取**入口快速拒绝**，最终配额交易仍在DB中核验Principal启用/应用归属/订阅/具体API范围。对内部配额服务非法密钥、伪造appId/routeId、不同version、重放chargeId设独立测试。

### R10｜前端产品与业务闭环（A–D，P1）
A只展示客户/应用/套餐/订阅及“应用凭证待启用”，不在首页展示虚构用量；B上线真正应用凭证、额度余额/账本后才开放计量页面；C Kafka统计/对账完成后再展示异步最新同步时间；D负责人手工覆盖1280px/375px与角色、失败状态。旧`/console/`已有领域与导航必须扩展而非推倒重做；旧`/test-console/`保持开发用途。

## 4. 开发分配建议

| 责任阶段 | 顺序 | 具体交付 | Gate |
|---|---|---|---|
| **V11-A** | A0→A1→A2→A3→A4→A5→A6 | 安全基线、增量DDL、ADMIN-only管理API、订阅行锁、产品页/中文指南、定向测试/证据 | 双管理员订阅互斥；Token未激活；旧Caller完全回归 |
| **V11-B** | B0→B1→B2→B3→B4→B5→B6 | 协议/能力门槛、MySQL余额/Ledger/Outbox、app命名空间凭证、可信Quota内部端点、Gateway非阻塞拦截、两网关并发与故障 | 5额度仅5grant、重复不扣、混合版本不绕过、跨月不二次扣 |
| **V11-C** | C0→C1→C2→C3→C4→C5 | Kafka可选依赖、Outbox租约、消费幂等、UsageDaily、失败重投、对账、产品页 | Kafka宕机扣量仍正确、重复后汇总只一次 |
| **V11-D** | D0→D1→D2→D3→D4 | 10万记录EXPLAIN/游标、四组负载、故障矩阵、V1/V11全部回归、人工产品体验/交付 | 可重现真实结果、全部阻断收口、负责人确认合并 |

建议在`docs/v1.1-openapi-platform`文档分支完成以上锁定后，新建`feat/v11-a-business`从设计最新SHA出发、Draft PR以设计分支为base。Codex**只做A**，每步不同Conventional Commits，后续B/C/D在上一块审查通过后再单独派工。

## 5. 可核对事实与未完成事项

- 已查V1源码：`CallerAuthFilter`只认`fg:auth:caller:`，`IdentityService.createCaller`写相同namespace；`AdminAuthInterceptor`对大多数/admin路径默认非VIEWER可写；`ReleaseService.build`非速率/并发类型当前标记`PolicyRules.PROTECTED`；现有表的MySQL基线不含V11业务对象；`/console/`当前已有用户业务产品界面。
- 已核对V11设计文件/A–D各块：目前皆为**提案，未运行新功能**。本审核仅产生文档与派工约束，不是代码、压测或生产安全认证。
- 参考官方资料：MySQL 8.4 `SELECT ... FOR UPDATE SKIP LOCKED`队列语义 https://dev.mysql.com/doc/refman/8.4/en/select.html ；Spring Cloud Gateway组合全局/路由过滤器顺序 https://docs.spring.io/spring-cloud-gateway/reference/5.1/spring-cloud-gateway-server-webflux/global-filters.html （项目实际5.0.x需本地编译/测试确认）。

**审查最终判定：设计方向通过，R01/R02/R03/R04/R05/R06为实施前需明确的P0约束；V11-A在安全基线审计后可开发，B/C/D暂不授权直接编码。**
