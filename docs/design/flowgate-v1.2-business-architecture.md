# FlowGate V1.2｜面向开发者的API市场、订阅交易与高并发流量治理总体设计

> **状态：设计提案，未编码、未迁移、未测试、未上线；已完成总体设计与V12-A详细合同；尚未开始业务编码，后续逐阶段经审核才派发Codex。** 依据[2026-11-10现状审计](../reviews/v12-current-state-audit-2026-11-10.md)的最新E功能分支；以[最终ADR](v12-architecture-decisions.md)及[一致性合同](v12-transaction-invariants.md)共同约束所有交易和抢购（冲突时优先采用最新ADR）。本文与V11-D任务并行保留，不代替V11正式性能/人工验收，也不自动合并旧PR。

## 1. 产品定位与不做的事

**定位**：统一开放第三方API，用户通过Portal自行发现API、创建应用、获得免费/付费月度权益、使用独立APP Key调用Gateway；管理员在Console配置API、SKU、活动和流量治理；平台用权威配额账本、可靠消息与对账保证业务可验证。

**完整业务链**：管理员发布可调用的API → 上架API商品及可售SKU → 用户注册/选择个人开发者主体/创建应用 → 免费领取或创建购买订单 → 模拟支付成功 → 异步可靠开通**指定API的某UTC月份权益** → APP Key和Scope → Gateway按**指定API权益池**授权与扣减 → V12独立异步日用量 → Portal/Console可解释的额度/拒绝原因。

**必须明确的非目标**：首版不接真支付/短信/邮件/税票/计费对账；不开放用户任意URL代理；不引入Nacos/Seata/Elasticsearch/Redis Streams新业务总线/分库分表/前端大框架；不把下游失败记退款、不让Kafka参与Gateway每个请求的同步授权。不宣称生产支付资质、跨Redis+MySQL+Kafka全局事务或exactly-once。

## 2. 用户、角色与权限：复用主体，不复用管理员权限

| 角色 | 入口/身份 | 能做的事 | 不能做的事 |
|---|---|---|---|
| 游客 | `/portal/`公开部分 | 查看已上架API、公开文档和套餐、注册登录 | 查看任意订单/Key/用量；调用受保护API |
| Portal用户 | 新`PORTAL_USER`主体、独立`fg:auth:portal:`会话 | 管理自己的开发者工作区/应用，订阅或支付自己的订单，查看余额/调用历史 | 调用`/admin/**`；管理别人Developer/App/Order |
| 开发者工作区OWNER | 新`fg_developer_member`关联现有`fg_developer` | 管理自己名下应用、Key、订单、权益；未来可扩充MEMBER | 通过猜测ID访问其他工作区 |
| 平台ADMIN | 现有`/console/` ADMIN | API与市场商品/活动/运营订单、补偿、风控、流量策略、发布 | 随意更改历史权威Ledger或隐式给用户退款 |
| OPERATOR/VIEWER | 现有管理会话 | 旧V1允许的编辑或只读/新增运营只读 | 无权模拟付款、重排权益、修改库存、管理第三方凭证 |
| APP_CALLER / 旧CALLER | Gateway独立Token | 对允许的具体路由授权调用，具体扣量由Control判定 | 登录Portal/Admin、直接修改订单/权益 |

**实现落点**：继续使用`fg_principal`的BCrypt/主体用途框架，为新用户新增`principal_type='PORTAL_USER'`而**不把它伪装成ADMIN**；表`fg_portal_user(principal_id PK,login_name_normalized UNIQUE,display_name,...)`保存Portal业务资料；表`fg_developer_member(developer_id,principal_id,member_role,UNIQUE(...))`形成可核查拥有关系。初次注册在同一MySQL事务创建Portal Principal、用户档案、个人Developer及OWNER绑定；旧管理员建的Developer不自动归个人，未来只能经管理员受控邀请/认领。建议Portal账号用`fg_principal.username='portal-<UUID>'`内部名，解决当前跨ADMIN/CALLER共享username唯一键；登录名只在Portal档案里规范化唯一。

**身份最小改动**：在现有IdentityService/Redis Token摘要实现上抽出可共用的创建/校验原语，但Portal新增`PortalIdentityService`做真实不同用途的Session校验；`/portal/api/auth`与`/portal/api/**`经独立Filter/Interceptor；服务端从可信会话解析principalId和developer_id并校验会员角色，**不相信JSON里的ownerId或query里的applicationId**。管理员会话与APP Key绝不能当Portal Token；Portal Token也不能直接发给Gateway充当APP调用凭证。密码BCrypt、统一登陆错误、最小登录/注册频率防护、登出、禁用用户与可重放安全会话必须测试。首版Token在同源前端内存中；若未来采用HttpOnly SameSite Cookie，还必须设计CSRF保护，不能以“默认浏览器会处理”替代。

## 3. 前端结构：Portal、Console、API数据面三个入口

| 入口 | 角色 | 组织方式 | 性能/安全特点 |
|---|---|---|---|
| `/portal/` | 游客/已登录开发者 | 新增`flowgate-control/src/main/resources/static/portal/`，独立`index.html`、`app.js`、`pages/market.js`等原生ES modules；独立导航/会话/路由 | 产品信息可公开缓存，个人订单/Key全部server ownership检查 |
| `/console/` | 平台运营 | 延续现有七域业务工作台：API接入、客户业务、治理策略、用量对账、发布中心、运行监控；新增“商品运营、订单与活动”页内入口 | ADMIN/OPERATOR/VIEWER权限沿用，非重建一套后台 |
| `/api/**` Gateway | APP调用方/在线调试 | 已发布路由；未来新增明确V12_API metering模式 | 不做订单事务/同步JDBC；只非阻塞内部Quota准入 |
| `/test-console/` | 开发/隔离诊断 | 保留已有开发验证台 | 不与Portal导航、Admin登录、正式消费行为混淆 |

建议Portal用户导航：**API市场（首页/分类/搜索/详情/文档）→ 套餐（免费/付费）→ 限时特价 → 我的订单 → 我的订阅 → 开发者应用 → API Key → 调用调试 → 用量/调用记录 → 个人中心**。首页以搜索、实际分类与可用商品为主体，不摆示意KPI、无法证明的假热门榜和营销标语。普通用户选择真实API、SKU、应用和确认订单，不填内部ID/JSON；没有可售API显示真实空态。

线上调试必须限制在平台已发布API/允许的方法、选定的应用及真实APP凭证；首版可在同源Portal页面中以**临时内存**保管用户主动提供的一次性App Token，由浏览器调用Gateway已允许的Origin；不在URL、浏览器localStorage/日志/截图保存Token。不得将Portal管理会话当APP Key，也不得提供服务器任意外部URL代理产生SSRF。若需要无需再次粘贴Key的在线调试，另行设计范围更小、短有效期的代理授权并通过Gateway可信准入，不能绕过配额。

现有Console原生JS/导航已完成响应式自动验证，Portal不必为了业务增加Vue/React/Vite/npm。两端可共用纯函数、格式化工具和不含会话的基础样式，**绝不共用admin专用API客户端或敏感状态**。

## 4. 新商品/市场与现有fg_api的关系

- **`fg_api`是基础接口契约，`fg_service`/路由/版本发布仍是唯一可调用数据面事实**。商品上架不能自动创API路由、改Gateway快照或允许任意上游。
- 新`fg_api_listing(id,api_id UNIQUE,title,summary,category_id,doc_markdown_sanitized,visibility,revision,created_at,...)`是可见产品信息；`fg_api_category`为运营分类。
- 新`fg_api_sku(id,listing_id,sku_code UNIQUE,quota_units,price_cents,period_policy,offer_version,status,...)`是不可变报价模板；订单快照冻结API、商品/版本、价格整数分、授权单位、UTC权益月份、购买应用。旧`fg_api_plan`继续服务V11 legacy app月套餐，不直接新增价格字段伪装成按API SKU。
- 商品可售的必要事实：后台 listing=LISTED、sku=ON_SALE、关联API启用且有当前实际发布可调用路由、V12节点具备支持的计量能力；订单创建还要查本地DB最新状态，不能相信缓存/前端“有货”。
- **API范围**：V12基准只售`API单品 × APP × UTC自然月`，不同API具有独立月度额度。首版没有“一买即可调用所有API”的隐含全站许可，也不凭Scope跳过购买。升级/加包用扩展权益规则，不重写既有V11全局配额。

市场MVP提供分类/关键词LIKE+必要索引/详情/真实套餐；收藏可用`fg_api_favorite`唯一`(portal_user_id,listing_id)`，不阻塞交易。排序“热门”只能根据已落库访问/授权统计得到，未积累样本时显示“暂无排名”；Redis ZSet由可靠读模型汇总更新，后续性能数据确需时才启用。Cache Aside只缓存**公开商品详情/分类**，键含版本及有界TTL+jitter，商品更新事务提交后失效；订单、价格最终计算、库存/权益永远读MySQL权威，不靠旧缓存决定支付或可售性。

## 5. 必须引入V12_API权益池，而非修改V11冻结额度

### 5.1 为什么不能沿用V11按App/月唯一余额

旧`fg_quota_balance`的`UNIQUE(application_id,period_yyyymm)`和`requireFrozenBalance`意味着同应用当月只能有一个冻结套餐；其`fg_subscription`没有api_id和order_id。若让用户分别买API A和B的额度，强塞旧余额会使A/B相互挤占、套餐切换冲突、付费后重置余额。**此方案禁止**。

### 5.2 增量并存方案（首选）

| V11 legacy | V12 新API权益 |
|---|---|
| `fg_subscription`：应用整月定义，ADMIN操作 | 新`fg_api_subscription`：`application_id + api_id + period_yyyymm`的**唯一BASE权益**、source/order及SKU冻结 |
| `fg_quota_balance`：`UNIQUE(app,month)`、一次冻结 | 新`fg_api_quota_balance`：`UNIQUE(app,api,month)`、total/remaining/version；只允许授权扣减及有审计的额外权益入账 |
| `fg_quota_ledger`：旧chargeId/应用月流水 | 新`fg_api_quota_ledger`：V12 chargeId UNIQUE、持有权益池引用和路由版本 |
| `fg_outbox_event`/`fg_consumed_event`/`fg_usage_daily`：旧V11独立用量链 | 新`fg_api_usage_outbox`/`fg_api_consumed_event`/`fg_api_usage_daily`，独立Kafka Topic/消费组，严格核对新Ledger；不混入旧Daily |
| 旧配额策略`USAGE_QUOTA` | 新显式策略/元数据`USAGE_QUOTA_V12`与Gateway特性`USAGE_QUOTA_V12`；同一实际Route不能同时命中V11和V12配额 |

**每次调用只扣一个权威池**：路由已发布的不可变计量模式决定V11_APP还是V12_APP_API；两者都不存在则严格拒绝计量API，不回退为不扣费；不得同时产生两笔Ledger；旧非计量Caller继续使用V1路径。V12安全部署要求所有接流Gateway具备新版本能力，沿用完整节点名单/版本/checksum/心跳门禁，防旧节点绕过。Gateway通过异步WebClient调用Control新内部授权，绝不把JDBC/Redis同步操作放进Netty线程。

**增量权益**：`fg_entitlement_grant(id,source_type,source_id,application_id,api_id,period_yyyymm,units,kind)`的来源唯一键防订单重复开通。首个BASE创建新`fg_api_subscription`和新月余额；以后同API同月仅允许有明确SKU类型`TOPUP`的增加额度，并在`fg_entitlement_grant`留痕。**首版默认只开放BASE，不开放同月升级/顶格重购**；续订按下一UTC月排队，最初不做自动续费/跨月滚存。V12权益冻结`period`及`unit`，后续SKU改价/改额度不追溯。旧V11月冻结定义完全不动。

**免费领取与付费统一**：V12-A仅允许每开发者/商品一次的`FREE_TRIAL`来源写入同一个`fg_entitlement_grant`/新API权益池，由MySQL事务确保唯一；V12-B付费后同一个`EntitlementGrantService`按`PAID_ORDER`来源开通。避免A写一套“临时免费订阅”而B重写所有赠送逻辑。免费不是模拟支付成功，标明来源。

## 6. 订单与模拟支付：不是点击购买即订阅

**冻结订单快照**：`fg_order(id,order_no UNIQUE,principal_id,developer_id,application_id,api_id,sku_id,offer_version,period_yyyymm,units_snapshot,amount_cents,source,activity_id,expires_at,status,version,...)`。金额使用整数分，价格来自受信商品SKU，不允许前端提交金额。按用户授权关系校验所选APP属于自己的Developer。

- 普通付费`POST /portal/api/orders`在同一数据库事务先锁APP并预占`fg_api_entitlement_slot`唯一BASE权益槽，成功后才创建`PENDING_PAYMENT`，`clientRequestId`作用域`(user_id,clientRequestId)`唯一并比较请求指纹；重试返回同一订单，不允许同键换SKU/APP/期间。
- 模拟支付`POST /portal/api/orders/{id}/simulate-pay`仅在受信用户确认、DB订单未超时/未关闭时执行业务状态CAS；同事务写唯一`fg_payment_record(provider='SIM',payment_ref UNIQUE)`及`fg_business_outbox(PAYMENT_CONFIRMED)`，更新订单为`PAID_PENDING_FULFILLMENT`。支付接口返回**模拟确认成功/待权益**，而不是立刻声称Gateway可用。
- 专用Kafka业务Worker消费支付确认，在MySQL事务中唯一写`fg_entitlement_grant(source='PAID_ORDER',sourceId=orderId)`，创建对应`fg_api_subscription`、新余额和订单`FULFILLED`；真正提交后才ACK，重复消费不重开权益。失败可重试、超限隔离并可受控人工重排；操作审计和“待开通”页面说明。
- 超时关闭只允许`PENDING_PAYMENT → CLOSED_TIMEOUT`，与支付使用同一订单行锁/CAS和DB时钟；支付先赢则不可取消，关闭先赢则支付不产生成功结果与权益；不用JVM系统时钟/前端倒计时当权威。首版不接真实资金，关闭不需要真实退款。
- 退款首版建议仅开放“**未使用的模拟权益申请**”或管理员受限冲正：订单`REFUND_PENDING→REFUNDED`、对应权益状态由待开通/未消耗确定；已消费单位不可改写或退款扣账本，若未来允许已使用后的真实退款需完整业务补偿方案。管理员不能直接删除历史Ledger和订单。

**原本用户点击“普通订阅”→“订单”→“模拟支付”→“权益开通”是唯一付费主线**。不能页面点击订单后直接调用老`/admin/subscriptions`；那是管理员Legacy管理合同。

## 7. 限时抢购：100份9.9元只是示例，不是已跑过指标

活动`fg_promotion(id,sku_id,begin_at,end_at,limit_per_user,offer_price_cents,status,...)`和MySQL库存`fg_promo_stock(activity_id PK,total,reserved,sold,version,...)`是权威；`fg_promo_purchase_intent(id,activity_id,user_id,application_id,sku_snapshot,request_id,status,...)`唯一`(activity_id,user_id)`（首版每用户每活动1次，**跨其多个APP也不重复**）；业务Outbox事件另表，避免污染旧C的USAGE_GRANTED与统计积压。

### 推荐生产语义（MVP，牺牲少量获胜者数据库写入换可恢复性）

1. 已登录且拥有APP的用户发起`POST /portal/api/promotions/{id}/claim`，带唯一clientRequestId；Gateway/Portal入口有界限速、活动发布时间权威。
2. Redis Lua用Redis TIME、冻结活动版本、库存可用量、用户唯一资格和预扣记录**同脚本执行**。失败时返回售罄/重复/活动未开始；**脚本成功只产生临时候选，不是订单成功**。资格有TTL和唯一claimId，跨请求返回同一候选状态。
3. **仅Redis筛出的候选**进入一笔MySQL事务：先锁应用并预占同一APP/API/月BASE权益槽，再校验活动/应用归属/SKU/权益期间；`UPDATE fg_promo_stock SET reserved=reserved+1 WHERE reserved+sold<total`条件更新；插入持久`fg_promo_purchase_intent(status='ACCEPTED')`和业务Outbox`PROMO_INTENT_ACCEPTED`。只在该**事务提交后**返回HTTP **202 QUEUED + intentId**；Redis已预扣但DB未提交绝不返回“已受理”。DB冲突/不可达时只返回失败或待重试安全状态，执行按claimId比较的Redis释放/过期恢复，不返回假订单ID。
4. 业务Outbox通过**现有Kafka Broker的新业务Topic/消费者组**投递，订单Consumer幂等创建`PENDING_PAYMENT`订单并把intent改为`ORDER_READY`。这里DB库存reserved已占位，消费者不二次扣库存；重复事件由业务唯一键和ConsumedEvent保护。UI轮询`GET /portal/api/purchase-intents/{id}`时明确区分“受理排队”和“待支付”。
5. 订单超时关闭时在同一MySQL事务`reserved-1`+订单/intent终态更新+业务Outbox`STOCK_RELEASED`；支付成功时`reserved-1,sold+1`，经订单B的唯一权益开通流程。补偿事件同步Redis临时库存/资格，但**MySQL条件库存永远兜底防超卖**。重复支付/取消/补偿至多影响一次，消费端晚到的订单创建不能复活已关闭intent。
6. Redis丢失、AOF回退、投递失败、Worker宕机：新抢购先**fail-closed**或标`REBUILDING`，由有界对账扫描MySQL的reserved/sold和尚未完成候选重建Redis库存视图；以活动epoch和Lua原子切换防止并发重建覆盖新预扣。Redis只做**高速预筛**，不拥有最终销售库存；DB已受理的intent与业务Outbox可恢复。若进程在Redis成功后、DB提交前崩溃，用户没有收到202，残留软预扣按TTL/版本重建恢复，容许短时少卖但绝不超卖；任何Redis临时候选不得声称已受理。

此方案的数据库写入**只发生在Redis筛出的最多可售候选及有限重试**，不是对所有数十万抢购请求逐个落MySQL订单；比“纯Redis扣库存后直接发送Kafka并立即告诉用户成功”更诚实、可恢复。未来若真实压测证实该赢者事务是瓶颈，再评估更激进的异步受理协议，前提是有持久可靠的请求回执与补偿，不能先用无保障Stream/队列绕过去。

**库存数字口径**：`total`总限量，`reserved`已被持久受理但未支付的占用，`sold`已模拟付款确认的最终销售；数据库约束`0<=reserved,0<=sold,reserved+sold<=total`。取消待支付只释放reserved；付款只在同事务`reserved--,sold++`；已消费权益的“退款”不自动重售库存。Redis展示余量只是缓存提示，用户订单状态读MySQL。

## 8. API/页面契约草案（只定语义，不进入Codex编码）

| 方法与路径 | 意义 | 关键权限及幂等 |
|---|---|---|
| `POST /portal/api/auth/register`、`login`、`logout`；`GET /portal/api/me` | 注册登录/本人信息 | 新PORTAL_USER会话，与Admin/APP完全分离，注册限速 |
| `GET /portal/api/catalog/apis`、`/{id}`、`/{id}/offers` | 公开市场/搜索/详情/报价 | 仅展示真实可上架产品，公开缓存不能决定成交 |
| `GET/POST /portal/api/apps`、`GET /portal/api/apps/{id}` | 自有应用列表/创建 | 服务端强制工作区OWNER/membership |
| `POST /portal/api/apps/{id}/credentials/issue|rotate|revoke` | 自助APP Key | 必须重用B代次及后端就绪门禁，一次性回显，不返回历史 |
| `POST /portal/api/free-grants` | 免费API月权益 | 唯一免费来源+同一权益服务；不得创建Legacy同月重复订阅 |
| `POST /portal/api/orders`、`GET /portal/api/orders`、`GET /portal/api/orders/{id}` | 创建/查询购买订单 | 订单快照、owner、idempotency key、稳定游标 |
| `POST /portal/api/orders/{id}/simulate-pay`、`POST .../cancel` | 模拟付款/取消 | 订单行CAS、支付/关闭竞态不可双赢 |
| `POST /portal/api/promotions/{id}/claim`；`GET /portal/api/purchase-intents/{id}` | 受理抢购与查询状态 | 202仅DB提交的持久intent、唯一限购、超卖双保险 |
| `GET /portal/api/subscriptions`、`/quota`、`/usage` | 本人API权益与用量 | 限自有APP，MySQL余额和C异步用量分开展示 |
| `POST /portal/api/debug/validate`（可选） | 在线调试参数/接口支持性检查 | 不接收任意外部URL，实际调用走Gateway |

Console添加`/admin/market/listings`、`/admin/market/skus`、`/admin/promotions`、`/admin/orders`及`/admin/commerce/reconciliation`，按现有ADMIN写权限并重新校验GET的敏感信息可见性。用户端GET默认不访问ADMIN业务API。

**统一业务错误约定**：401未登录/Token无效，403跨租户/Scope，404不泄露存在性，409重复申请/订单终态或本月权益冲突，429限流/售罄按稳定code区分，503数据库/Kafka/Redis未就绪或关键结果不确定，202仅已持久受理且排队。所有响应有requestId、业务code，不附凭证/SQL/原始Kafka payload。支付状态和真实权益状态要分开查询。

## 9. 管理端商业运营：服务治理与交易拆开职责

原Console七域导航保留。推荐“客户业务”扩充为**开发者/用户、应用、SKU与订单**，“API接入”扩充**市场上架/说明/分类**，“运行监控”加入**活动/交易/权益对账**或新一个明确的“商品与交易”一级业务域（以实际UX验收决定）。管理员可以管理库存额度、活动时间、活动状态和价格，但**发布后历史订单快照不可改**；正常订单取消/支付走真实状态机，异常干预有ADMIN专用审计与理由，不提供“直接把余额设100”的后门。

上线顺序建议：先确认Gateway发布了私有计量API并完成V12节点能力部署，再将SKU标为可售；活动发布前预热Redis并检查MySQL库存、Kafka业务Worker/Outbox健康和补偿任务就绪，**任何关键门禁UNKNOWN都不能开始抢购**。操作台状态“商品可见/正在售卖/付款成功/权益已开通/配额可调用”必须分开展示。

## 10. 技术栈与交付约束

继续：Java17、当前Spring Boot4、Gateway WebFlux、Control MVC/MyBatis-Plus、MySQL8.4、Redis7/Lua、当前Kafka4.1.1、Maven、原生ES Modules。最初不新增微服务，Portal静态根和Portal/Commerce Java包留在Control；Gateway只增加新计量策略识别和非阻塞内部调用。Kafka复用**同一个Broker**，业务Topic/ConsumerGroup/Outbox独立；不能让订单消息走现有`UsageGrantedEvent.parse`而污染C读模型。必要新增SQL版本`sql/flowgate-v12-a.sql`、`-b.sql`、`-c.sql`，增量且不可直接迁移用户常驻库。所有关键Java长方法中文注释按`// 1.`、`// 2.`、`// 3.`。

**当前内容均为待审方案**，源代码未新增、性能/安全数字未预填；阶段路线在`docs/progress/v12-roadmap.md`。
