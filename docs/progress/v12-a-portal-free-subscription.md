# FlowGate V12-A｜普通用户Portal、API市场、自助免费权益、APP调用｜详细Codex实施合同

 > **当前状态：V12-A功能实施与定向验证完成；首次A7静态复审已发现三项P1需要补丁/定向故障验证，负责人手测尚未执行；负责人于2026-11-10明确授权V12改用RabbitMQ，V11保留Kafka，并要求功能优先、定向验证。以下V12新Kafka要求由ADR-13覆盖，历史V11要求保持不变。** 前置审计`docs/reviews/v12-current-state-audit-2026-11-10.md`、总设计`docs/design/flowgate-v1.2-business-architecture.md`、正式ADR`docs/design/v12-architecture-decisions.md`、一致性合同`docs/design/v12-transaction-invariants.md`。本阶段**不允许实现B普通订单/模拟支付、C抢购/优惠券、D性能实验**。
>
> **分支栈建议**：`feat/v11-e-local-delivery` → `docs/v12-platform-business-redesign`（本设计）→ **仅本地Codex新建**`feat/v12-a-portal`；Draft PR base只对准直接设计父分支。Codex先确保设计分支远端最新HEAD，`git fetch`并比对同一源码树，不重置未提交工作/不挪旧PR基线。

## A-00 目标、运行基线、停止线

一个普通用户不拿ADMIN密码，仅用`/portal/`：注册→实际市场搜索→免费API SKU→创建自有APP→免费领取**APP/API/当前UTC月**额度→签发受控APP Token→Gateway真实调用5次成功/第6次429（测试专用Free5）→权威余额/流水可读→可选Kafka异步日报追平；另一个用户用猜ID无法看/改其应用、凭证与配额。

- V11现有独立角色：ADMIN/OPERATOR/VIEWER；旧CALLER；APP_CALLER。A新增PORTAL_USER，不改它们的历史明文/Token/Session协议。
- 新功能服务端默认关闭，建议`FLOWGATE_PORTAL_ENABLED=false`、`FLOWGATE_V12_QUOTA_ENABLED=false`；关闭时旧V1/V11不查V12表、不访问新Topic、不创建Portal模拟用户；测试仅隔离E专用环境打开。
- A不建设商业订单；商品SKU结构为未来B设计，但A仅创建`FREE_BASE`(price=0)并支持列表、领取。预留`PAID_BASE`枚举的数据库约束可以定义，但**绝不在A提供“现在购买”假按钮**。
- A不要求用户手填数据库ID、策略ID或JSON；ADMIN仍可在Console上架商品并配置V12路由/发布。普通用户不得进入后台。
- 保留现有JDK17/Boot4/Control MVC+MyBatis-Plus、Gateway非阻塞WebClient、Redis7、MySQL8.4、Kafka4.1.1和原生ESM；不引入新微服务/前端npm工具。

### 阶段0真实盘点必须回填

`git status -sb`、远端设计SHA、JDK17、Maven、原E脚本`python3 scripts/v11-local.py doctor`、初始Java75/9个Node/原B23+C26真实Smoke（按测试安全条件执行），保存真实退出码和环境。检查原`.env`哈希/卷/PID只读，不提前写“通过”。

## A-01 增量SQL明确字典（`sql/flowgate-v12-a.sql`）

**必须采用`CREATE TABLE IF NOT EXISTS`加显式索引和CHECK/UNIQUE/FK，只在隔离新库安装；不修改/清空`sql/flowgate-init.sql`、`flowgate-v11.sql`、`v11-c.sql`及现有用户库。** 使用DB会话`SET time_zone='+00:00'`。V12-A新增表清单：

| 表 | 首版核心字段/约束/索引 | 权威意义 |
|---|---|---|
| `fg_portal_user` | `principal_id BIGINT PK FK fg_principal`、`login_name_normalized VARCHAR(100) NOT NULL UNIQUE`、`display_name`、`status ACTIVE/SUSPENDED`、timestamps | 用户登录名与个人资料；Principal类型PORTAL_USER |
| `fg_developer_member` | `developer_id FK`、`principal_id FK`、`role OWNER`、`status ACTIVE`，`PK(developer_id,principal_id)`，另建`(principal_id,developer_id)`索引 | 客户工作区归属，不以用户ID直接当developer_id |
| `fg_api_category` | `id PK,code UNIQUE,name,status,sort_order` | 管理可见分类 |
| `fg_api_listing` | `id PK, api_id FK fg_api UNIQUE, category_id FK, title, summary, doc_text, status DRAFT/LISTED/OFFLINE, revision,created_at,updated_at` | 市场商品展示，与实际API路由分开 |
| `fg_api_sku` | `id PK,listing_id FK,code UNIQUE,kind FREE_BASE/PAID_BASE,quota_units [1..1000000],price_cents>=0,period_policy CURRENT_MONTH/NEXT_MONTH,status,revision,timestamps` | 不可修改已引用的价格/单位，B以后通过新SKU版本更新，A只售FREE_BASE |
| `fg_portal_free_claim` | `id PK,principal_id FK,api_id FK,application_id FK,period_yyyymm CHAR(6), UNIQUE(principal_id,api_id)` | 同一用户对同一API终身仅可免费领取一次；换APP不可重复 |
| `fg_api_entitlement_slot` | `PK(application_id,api_id,period_yyyymm)`，`state AVAILABLE/HELD/GRANTED`,`holder_type FREE_GRANT/ORDER/PROMO`,`holder_id`,`version`，状态/持有者必须匹配 | 唯一BASE权益槽；A直接GRANTED，B/C可HELD，冲突必须409 |
| `fg_entitlement_grant` | `id PK, source_type FREE_TRIAL/PAID_ORDER, source_id BIGINT, application_id,api_id,period,kind BASE,units,created_at,UNIQUE(source_type,source_id)`，`INDEX(app,api,period,id)` | 不可变唯一权益来源；不能从页面直接改余额 |
| `fg_api_subscription` | `id PK,application_id,api_id,period_yyyymm,effective_at,expires_at,grant_id UNIQUE,units_snapshot,status, UNIQUE(app,api,period)` | APP/API/UTC月唯一BASE；effective/expires为半开月区间 |
| `fg_api_quota_balance` | `id PK,app_id,api_id,period_yyyymm,subscription_id UNIQUE, total_units,remaining_units,version,UNIQUE(app,api,period), CHECK(0<=remaining<=total)` | 新API配额权威余额池 |
| `fg_api_quota_ledger` | `id PK,charge_id CHAR(36) UNIQUE,request_fingerprint,app_id,principal_id,generation,api_id,route_id,applied_version,policy_id,period,units,remaining_units_after,created_at, INDEX(app,api,period,id)` | 新模式授权不可变扣量记录 |
| `fg_api_usage_outbox` | `id PK,event_id UNIQUE,charge_id UNIQUE,event_type='USAGE_GRANTED_V12',payload_json, status,attempt_count,next_attempt_at,lease_owner,lease_until,last_error_code, INDEX(status,next_attempt_at,lease_until,id)` | V12专属异步事件，不能混到V11原Outbox |
| `fg_api_consumed_event` | `event_id PK,charge_id UNIQUE,payload_sha256,consumed_at` | V12 Kafka严格消费去重 |
| `fg_api_usage_daily` | `PK(day_utc,application_id,api_id)`,`authorized_units,grant_count,updated_at`；`INDEX(app,day,api)` | V12独立日报，避免同一APP/API/day的V11/V12混账 |

**落地时先复核MySQL8.4对CHECK、`TIMESTAMP(3)`、组合PK、FK字段大小和主键设计的可运行语法**；上表是字段/约束合同不是可直接执行DDL。即使A只用部分未来B列，也不能为方便前端给订单预先伪造记录。

**SQL事务约束**：
- 注册：一笔DB事务插Principal(PORTAL_USER/BCrypt)→PortalUser(normalized唯一)→Developer自动唯一code→DeveloperMember OWNER；Redis登录会话**在事务提交后**才写；若Redis写失败返回可重试登录，不能返回无效会话；同名注册只有1成功。
- 免费领取：DB锁`fg_application FOR UPDATE`→校验开发者member/APP ACTIVE/上市SKU & API有已应用V12路由→冻结当月UTC `yyyyMM`→锁权益slot（不存在INSERT IGNORE后CURRENT READ）→唯一`fg_portal_free_claim`→slot=GRANTED→`fg_entitlement_grant`→`fg_api_subscription`→`fg_api_quota_balance`一次提交；重复同一用户同一API要返回`FREE_ALREADY_CLAIMED`且不重置余额；已有任何当月BASE要409而不是换池。
- V12授权：锁定APP/Developer/凭证与版本规则/已授权API，先查同chargeId并比较指纹；跨月重试旧chargeId返回旧授权；新授权只针对当前UTC月，有效V12订阅且对应余额；锁余额扣减、写Ledger、写新Outbox同事务；扣量失败429且不生成Ledger/Outbox；重复授权不重复减。不可更改V11原表。
- 若异步关闭，Outbox保留待处理，Portal只显示权威余额/流水和异步延迟，**不虚构日报**。

## A-02 身份、ACL与所有权（Control）

**建议文件**：
`flowgate-control/src/main/java/com/flowgate/control/portal/PortalIdentityService.java`、
`portal/PortalAuthFilter.java`、
`portal/PortalOwnershipService.java`、
`portal/PortalAuthController.java`，
及相应`entity/mapper`；复用原`IdentityService`的摘要加盐无关SHA256/BCrypt思想，但**不让原管理`identity.admin()`接受PORTAL_USER**。

固定API：
- `POST /portal/api/auth/register` body `{loginName,password,displayName}` →201 `{userId,displayName}`；服务端自动创建Developer+OWNER，不返回敏感主键关系和密码。用户名trim+NFKC/小写的精确规范化规则固定在`PortalIdentityService`，限制3..64安全字符；密码8..72 UTF-8字节并限制请求体总大小，BCrypt校验；错误`400 VALIDATION_ERROR`/`409 LOGIN_NAME_TAKEN`。
- `POST /portal/api/auth/login` body `{loginName,password}` →200 `{token,expiresInSeconds,role:'PORTAL_USER'}`，无用户和错误密码同样401；Redis`fg:auth:portal:sha256(token)`只在成功发布后返回token；TTL参考ADMIN有限期如1800秒。
- `POST /portal/api/auth/logout`、`GET /portal/api/me`；`GET /portal/api/my-developers`，只返回本人会员身份的工作区；注册创建的个人Developer不可从Admin里直接无审计转给陌生用户。
- 身份错误：未登录401、已登录非owner跨域404、停用403；管理ADMIN Token/APP Token/旧CALLER Token送到Portal →401。Portal Token去访问`/admin`/Gateway计量→401。
- 注册与登录用最小限速（使用已有Redis，不自行增加中间件；按来源+规范化登录名限频）；只允许可信本地/配置Origin；前端不存Token到URL/localStorage/sessionStorage，不将密码/原始响应写日志。

**安全重点**：目前V11`AdminAuthInterceptor`和`V11ManagementFilter`只处理`/admin`；新`/portal/api`必须独立安全过滤，不能由于MVC默认开放让`GET /portal/api/orders/{other}`绕过校验。所有Controller/Service按当前登录Principal校验app.developer与member，任何client body userId/actorId一律拒绝。不要把`/portal/api/**`也代理到Gateway动态API路由。

## A-03 商品与市场（Control+Console）

**建议文件**：
`.../market/MarketCatalogService.java`、
`.../market/MarketAdminController.java`、
`.../portal/PortalMarketController.java`；
对应`ApiCategoryMapper`/`ListingMapper`/`SkuMapper`。

管理API（仅ADMIN写；读由现有业务运营ACL配置）：
- `GET /admin/market/categories`、`POST /admin/market/categories`
- `GET /admin/market/listings?afterId&limit`、`POST /admin/market/listings`、`PATCH /admin/market/listings/{id}`
- `POST /admin/market/listings/{id}/skus`（A只允许0分钱/FreeBase、固定units、当月有效；修改额度/价格必须建新SKU）
- `POST /admin/market/listings/{id}/publish`；检查所关联API至少有一条可信已应用V12计量私有路由，SKU有效，当前V12接流节点全部支持，无缺表/开关，不能把草稿保存等同网关配置发布。
- 新接口必须接入AdminAuthInterceptor.v11Path的管理名单并有真正后端ADMIN写权限；不能只依赖Console隐藏按钮。
用户API：
- `GET /portal/api/catalog/categories`
- `GET /portal/api/catalog/apis?q=&categoryId=&afterId=&limit=`；MySQL稳定游标分页、白名单查询，最多50；不能在所有公开API里返回上游真实地址/内部Key、内部配置；
- `GET /portal/api/catalog/apis/{listingId}`，`GET .../offers`：真实上架状态、API名/方法/公开路径、文档、免费SKU（可领取前必须再验证当前可信发布/节点/权益）；API详情不展示假评分/假成交量。
- 缓存A只保留最小可选：先MySQL普通索引/只读缓存Disabled，确认动态更新后可增加短TTL公共详情Cache Aside（**不得改变交易权威**）；Redis ZSet榜单到D阶段。

## A-04 APP/Key与免费领取（Portal+Console）

- `GET /portal/api/apps?afterId&limit`与`POST /portal/api/apps {developerId,code,name}`：先从会话取得OWNER，再调用复用业务保存/新的受限方法；Application的`developer_id`创建后不可变。不能把已有仅ADMIN的`DeveloperApplicationService.saveApp`暴露成`/portal`无ACL直通。
- `GET /portal/api/apps/{id}`、`GET /portal/api/apps/{id}/credentials/status`；`POST /portal/api/apps/{id}/credentials/issue|rotate|revoke`，与旧ADMIN使用同一个`AppCredentialService`及代次；签发/轮换先校验B完整可信门禁**及V12新节点feature**，不能在配额OFF时偷偷发Token。撤销仍可在OFF时应急执行。Scope必须在本人可售/已获权益API的范围白名单中，不得自报平台管理Scope。
- `POST /portal/api/free-grants` body `{applicationId,skuId}`，服务端推导apiId/真实0元报价/当月/units，验证owner+APP+listing+V12已发布路由；成功201 `{entitlementId,applicationId,apiId,periodUtc,totalUnits,remainingUnits}`；同用户重复或同APP/API/month已有BASE返回409，冲突不得重复增加额度；不得调用老`PlanSubscriptionService.subscribe`。
- `GET /portal/api/subscriptions?applicationId=&afterId=&limit=`、`GET /portal/api/quota?applicationId=&apiId=&period=`、`GET /portal/api/ledger?applicationId=&apiId=&period=&afterId=&limit=`；每次都后端校验OWNER；余额不存在必须返回`NO_ENTITLEMENT`而不是0；额度耗尽返回明确0并可追Ledger。
- API Key创建后**一次性展示**并可在个人应用下轮换/撤销；依B当前实际TTL3600秒，不标永久Key；刷新或离开页面清空明文。用户调试先提供复制安全示例/curl（占位Token不嵌入URL），**不要A中实现任意URL代理/CORS绕过**。
- 管理Console用真实新菜单添加商品/分类/SKU/上架，客户与应用管理保持原20能力，新增入口无开发测试标语。页面展示真实SKU价格0元/额度、在售/不可售原因，不用内部数字ID要求普通用户手动填写。

## A-05 V12 Gateway授权及异步用量专线（Contract/Gateway/Control）

**Contract**
- 固定新`USAGE_QUOTA_V12`（如unitsPerCall 1..100）及`ENFORCED_QUOTA_V12`，旧`USAGE_QUOTA`和`ENFORCED_QUOTA_V1`原样；`QuotaPolicy.Plan`携可信`mode`枚举/值，旧已存schemaVersion1的快照序列化与checksum不修改。
- `PolicyRules.validate()`和`QuotaPolicy.freeze()`确认同Route只可一个计量策略，V12只绑定私有API/Route且API真实ID冻结，草稿冲突/旧回滚/同版checksum必须测试。
- 发布`ReleaseService.publish()`为V12显式计量策略检查全部可信节点Feature`USAGE_QUOTA_V12`与开关/版本/checksum，候选上线前不得回落普通Caller路径。旧V11发布仍维持原能力门禁。

**Gateway**
- `QuotaAdmissionGlobalFilter`仅按可信RouteMetadata区分V11/V12调用`/internal/quota/charge-v12`，不把分支写成同时请求两条；新模式继续要求`fgapp_...` Token，旧Caller Token拒绝；无/错Scope403、无权益403`NO_ACTIVE_API_ENTITLEMENT`、余额不足429`QUOTA_EXHAUSTED`、未知/DB故障503 fail closed。
- 复用B现有同chargeId短重试、inflight≤64、每跳限时、非阻塞WebClient和取消释放；不能读jdbc或在Netty做同步Redis。旧V11配额Grant/2026年11月Free5测试全部保留。
- 修改节点可信能力上报，新增`USAGE_QUOTA_V12`只有Gateway真实支持才上报，完整名单不在线/能力不足停止V12签发/发布；不得只凭布尔开关确认支持。

**Control**
- 新`ApiQuotaChargeService`在独立事务内使用真实DB应用、成员无须每次查Portal登录（APP身份来自B独立凭证）、APP关联、Scope、新权益、冻结余额/版本、Ledger和V12新Outbox；内部端点仍只能可信loopback+internalKey访问，不接受客户端伪造appId。
- 新`ApiUsageGrantedEvent`只严格固定版本字段，`ApiUsageOutboxPublisher`与`ApiUsageKafkaListener`使用与C类似租约/有界退避/唯一幂等与SENT重排策略，独立`flowgate.usage.v12` Topic/Group及三表。异步OFF时不构造Consumer/Topic、只记录NEW，不回滚成功授权。原C`UsageAggregationService`和`UsageKafkaConfiguration`保持V11语义。
- V12日报/对账首版增加`/portal/api/usage`只返回**真实已消费的日汇总并标pending**；对账管理员必须按V12 Ledger/Outbox/Consumed/Daily源做只读完整性检查；不得把“未消费”判成MISMATCH或把当前UTC日显示最终MATCH；V11旧对账未污染。
- 不能用Kafka消息发“扣额成功”当权威授权，不能因为下游5xx或用户取消而退月额度。

## A-06 Portal用户体验（原生HTML/CSS/ESM）

文件：
`flowgate-control/src/main/resources/static/portal/index.html`、`style.css`、`app.js`、`api.js`、`navigation.js`、`pages/market.js`、`pages/apps.js`、`pages/free-grants.js`、`pages/usage.js`，可按最少文件组织但不能混入admin session。

| 页面 | 普通用户真实操作 | 错误与未完成提示 |
|---|---|---|
| 首页/市场 | 分类、搜索、真实已上架API、稳定分页 | 无上架项给可信空态，不放假数据/虚构热门 |
| API详情 | 方法/路径/公开文档、SKU免费额度与剩余本月日期、可用应用选择 | 不可售/节点未Ready显示不可领取及原因 |
| 注册/登录 | 正常用户名、密码、退出；注册自动个人开发者主体 | 重复名409/登录401/会话刷新失效 |
| 我的应用 | 创建APP、查看已有、凭证签发/轮换/撤销及一次性回执 | App Token 3600秒、离页即擦；不展示历史明文 |
| 我的权益 | 选择应用+API，领取一次FreeBASE，显示当月余额 | FREE_ALREADY_CLAIMED、同月已有BASE、未开通清晰区分 |
| 调用记录 | Ledger权威准入单位与Kafka日报延迟说明 | 429耗尽、503暂不可用不是“0使用量” |

A阶段不制作“我的订单/限时特价/优惠券支付成功”可点击假功能；导航可以暂不显示，未来B/C随着真业务上线再加。不在前端跨源偷偷发送后台管理员Token；调试能力在A保留安全调用示例，真正交互调试后续有明确Gateway CORS/权限模型才实施。

## A-07 测试证据矩阵与手动演示

**测试不能只写Mock/Node成功。** 每子阶段保留最少针对性JUnit、Node契约与独立随机Compose真实HTTP：

| 类别 | 必须自动验证 | 失败不可标完成 |
|---|---|---|
| A0认证 | 真双并发注册同名、普通账号vsADMIN/caller/APP四类Token互不互认、密码统一401、用户禁用/跨租户按404 | 只隐藏前端按钮 |
| A1市场 | 上架必须真实发布V12私有计量Route并节点就绪；停用/回滚/改SKU价格不能返回假“可领取”；SQL稳定分页和HTML安全显示 | 只展示静态JSON/假商品 |
| A2应用/Key | 两Portal用户互查应用、Key、额度均403/404；旧Admin可管理，普通用户不可发ADMIN凭证；Key只显示一次+轮换旧代次不能扣额 | 只有DOM禁用 |
| A3免费权益 | 100并发同用户/同APP/API/month只1 grant，换APP同API不能再免费领；不同用户独立领，另一个API权威额度隔离；旧V11月冻结未变 | 以Redis去重替代MySQL唯一 |
| A4网关 | 多Gateway共享MySQL正确扣额；5次200+1次429，429下无新Ledger/Outbox，重复chargeId返回原授权；DB/Redis/旧节点失效关闭；旧V11 B23/C26回归 | 直接调用Controller模拟“网关已通” |
| A5异步 | 原V11用量、V12用量不同Topic/表，100次重复新消息只聚合一次；停Kafka再恢复V12 Ledger=Consumed=Daily，今日不假MATCH，跨日不误归属 | 把V12事件插进旧Daily |
| A6浏览器 | 真实Chromium 1280/375 登录、搜索、创建APP、领取、Key回执、额度历史、退出和键盘；OWNER两账号跨租户HTTP真正拒绝；真人待负责人实测 | 只跑静态测试/模拟DOM |

实际脚本建议`scripts/v12-a-smoke.py`、`scripts/v12-a-portal-test.mjs`、`scripts/v12-a-browser.py`，复用E的独立启动安全模型，但不碰原`.env`和旧容器。旧B23/C26、Java75、Node9历史单独记录，若新测试导致运行次数变化按真实重新统计，不复制旧通过数字作为本轮成绩。

**用户手动验收最小路径**：
1. 隔离环境确认V11-E自身doctor，Admin先发布私有GET、V12新计量规则并等待全部网关真实applied；在Console上架该API的Free5 SKU。
2. 浏览`/portal/`注册小李→自动个人Developer→创建`portal-demo`应用；领取Free5当前UTC月，看到总额5剩余5。
3. Portal签发一个APP短期Token，展示一次性遮盖；从真实Gateway同路线连续调用6次，前5个200，第6个429；刷新权威余额是0、五条Ledger，不因下游返回失败自动补。
4. 另一个普通用户注册小王，尝试用同一appId/余额/Key管理路径，服务器不泄露；为其自己APP领Free5，不借用小李权益。
5. 如开启Kafka，等V12新异步日报追平；普通用户可以看到“授权统计”与“可能延迟”，管理端可查分模式真实对账；旧V11 B/C正常。
6. 375px/键盘/前进后退/离页Key消失，不依赖任何管理员手填ID/JSON/假活动按钮。

## A-08 分段提交/验收进度

| 段 | 代码任务 | 进度 | 证据文件 |
|---|---|---|---|
| A0 | 锁定隔离基线/用户资产/接口与DDL审阅 | 已实施/定向验证 | `v12-a-functional-smoke.json` |
| A1 | 主体注册/Portal会话/数据权限 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A2 | 市场分类/Listing/SKU/Console上架 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A3 | APP自助/Key复用+免费BASE唯一权益/余额 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A4 | Contract与Gateway显式V12配额模式/旧快照安全 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A5 | V12专属Outbox/RabbitMQ日报/只读对账 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A6 | Portal全流程用户体验、独立全链路Smoke/证据 | 已实施/定向验证 | `v12-a-functional-smoke.json`、`v12-a-browser.json` |
| A7 | ChatGPT远端源码+证据复审、负责人手动验收 | 首次静态源码复审完成：P1-A01～A03待修；真实故障复核与负责人手测未执行 | `docs/reviews/v12-a-*.md` |

**任何子段开始后**每次提交必须同步Portal/Console对应真实功能与中文帮助，不允许代码先行、页面持续是半成品；复杂Java方法用中文`// 1.`、`// 2.`、`// 3.`注释。单段失败必须停止并记录原因，未证明成功不提前写完成。只有A真正通过后才设计或下发V12-B编码任务。

## 本轮最终实施状态

详细功能、命令、失败修复和未执行矩阵见[v12-a-implementation.md](v12-a-implementation.md)。负责人明确选择V12 RabbitMQ、保留V11 Kafka，并缩小验证为必要定向检查；不能继续把正文旧Kafka合同或大矩阵视为已执行。A7仍未执行。
