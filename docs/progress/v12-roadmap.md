# FlowGate V1.2｜业务化升级阶段路线与验收门槛（2026-11-10）

> **状态：总体设计及关键ADR已定稿、V12-A详细实施合同和Codex单阶段提示词已备好；**无任何V12业务代码、迁移或运行结果，未启动Codex。 当前最新V11功能候选基线`feat/v11-e-local-delivery@b7f2a88794a6936d1e9854408e27d6f1f5387915`；新设计专属`docs/v12-platform-business-redesign`，不修改E/D/旧PR。主文档：[总体业务架构](../design/flowgate-v1.2-business-architecture.md)，权威规则：[V12 ADR](../design/v12-architecture-decisions.md)与[一致性合同](../design/v12-transaction-invariants.md)，事实依据：[仓库审计](../reviews/v12-current-state-audit-2026-11-10.md)。

## 0. 决策：先锁业务合同，再逐阶段编码

首版商业闭环固定下列**设计规则（尚未开发）**；B/C的细化表结构、状态机与持久Intent回收见[订单与抢购蓝图](../design/v12-commerce-order-promotion-blueprint.md)，A的可执行设计见[V12-A合同](v12-a-portal-free-subscription.md)：
1. Portal用户+个人Developer、上架真实API市场、创建APP与Key；
2. **V12专属APP-API-UTC月权益池**，Legacy V11 App全局月池保留并存，路由显式只选一种扣量模式；
3. A先只支持一份免费BASE真实权益；B支持付费订单/模拟结算/可靠开通；C支持活动库存100份示例与异步订单；
4. 同月升级、加包、自动续费、退款/优惠券复杂组合不作为首版强制功能；下一月续订可以支持单个BASE排队；首版只做风险清楚的受控模拟退款或明确`NOT_SUPPORTED`，不假装自动扣回已使用额度；
5. 保留V11-D未实施的SQL/四组公平压测和E6负责人真人手测，不把新V12阶段编号覆盖旧验收记录。

建议PR堆叠：现有`feat/v11-e-local-delivery` → `docs/v12-platform-business-redesign`（本轮设计，Draft）→ 用户确认后`feat/v12-a-portal` → `feat/v12-b-orders` → `feat/v12-c-flash-sale` → `test/v12-d-final-acceptance`。新阶段Codex只读对应阶段详细合同，不跨阶段擅自实现。**目前未创建任何V12功能分支/业务SQL/源码；仅为A写出[完整实施合同](v12-a-portal-free-subscription.md)和根目录[Codex提示词](../../CODEX_V12_A_EXEC_PROMPT.md)，待单独执行与复审。**

## V12-0：进入开发前的基线与选择题（不新加产品业务）

**前置**：负责人批准本设计，审计确认最新E而不是main；本人用`docs/guides/v11-local-delivery.md`完成E6关键页面手动抽查或明确留下延期清单。旧V11-D至少冻结测试计划和隔离环境，优先安排V11旧计量性能基线，避免未来无法归因V12增加的开销。

**只做**：`git fetch/status`和V11-E真实HEAD核对，Java17全模块`test package`、9套Node、E现有B23/C26原场景，保留旧证据不覆盖；只读现有用户卷与.env，不激活用户Kafka/计量；新隔离样本库和数据版本快照。负责人明确安全测试目标及是否允许新增独立演示库。核查当前`fg_principal`用户名约束、SQL时间类型和C事件格式，以设计文档的显式选择写入ADR。

**退出门槛**：旧业务稳定基线、个人用户/工作区模型确认、APP/API权益模型确认、按V12显式meteringMode的兼容计划确认，Codex尚未触碰业务代码。

## V12-A：普通用户、API市场和免费API自助领取（最小可演示）

**目标**：一个完全不具备ADMIN权限的Portal用户，通过浏览器自己注册/登录、搜索上架的真实API、创建自己的Developer应用、领取一次免费的**指定API×当月**权益、签发APP Token并经Gateway成功调用，在个人中心看到权威月余额。即使没有付费订单/Kafka活动也有独立业务闭环。

| 子段 | 必须实现的内容 | 关键文件与数据 |
|---|---|---|
| A0身份与归属 | PORTAL_USER用途隔离、注册/login/logout、受限个人Developer与`fg_developer_member`；数据库唯一、登录频控、OWNER检查 | `flowgate-control/.../portal/PortalIdentityService.java`、`PortalAuthFilter.java`、`sql/flowgate-v12-a.sql` |
| A1可售市场 | Listing/分类、SKU不可变报价、上下架、真实API发布校验、搜索/详情/文档；缓存只对公开数据，先MySQL筛选 | `.../market/ApiMarketService.java`、`PortalCatalogController.java`、`fg_api_listing/category/sku` |
| A2 APP/Key | Portal只读/创建自有APP，复用AppCredentialService代次/Scope/一次性显示；显式B就绪与V12能力门禁；跨工作区禁止 | `.../portal/PortalApplicationController.java`、`AppOwnershipService.java`，复用旧B实体 |
| A3 V12权威权益 | `FREE_TRIAL`每Portal用户/API终身唯一，APP/API/月的唯一BASE槽、同事务创建V12 Grant/订阅/余额；新增V12 quota mode/ledger与**独立V12用量Outbox/日报**，旧V11原账本和C版本仍兼容；必要时先发布V12安全路由 | `.../entitlement/EntitlementGrantService.java`、`ApiQuotaChargeService.java`、`fg_api_subscription/entitlement_grant/api_quota_balance/api_quota_ledger`，Gateway与Contract最小变更 |
| A4 Portal成品 | 首页搜索/分类、API详情和真实文档、注册登录、应用、Key、免费领取、余额；每个页面真空态/错误和自助流程中文指南 | `static/portal/index.html`、`portal/app.js`、`portal/pages/*.js`、`docs/guides/v12-portal-guide.md` |

**API骨架**：`POST /portal/api/auth/register|login|logout`、`GET /portal/api/catalog/apis`、`GET /portal/api/catalog/apis/{id}/offers`、`GET/POST /portal/api/apps`、`POST /portal/api/free-grants`、`GET /portal/api/subscriptions`和`/quota`。用量明细可暂显示“授权流水，异步统计稍后”，不要伪造C数据。

**安全与并发**：用户不能调用ADMIN接口，不能猜到其他用户APP凭证或用量；同一个免费SKU/用户/月份两次领取仅一笔权益；V11原App/月订阅、历史chargeId和旧Caller不受影响；V12 route元数据显式控制不双扣/不降级到V11老策略。

**定向验收**：Java服务/契约/Gateway新旧测试；独立MySQL/Redis真实HTTP两用户交叉ID访问403/404、100并发免费领取仍唯一、旧V11 Free5的5/6依旧生效、V12 API-A额度不能调用API-B、Key轮换/撤销代次；Node Portal与Console中文表单；真实1280/375浏览器的完整免费链路。写独立脱敏`docs/progress/evidence/v12-a-*.json`，保留V11所有证据。

**阶段终点**：普通用户已可独立免费使用API，不用管理员手建Developer/APP；**尚未有订单/模拟支付/真实秒杀**。A完成由主线复审后才启动B。

## V12-B：普通订单、模拟支付与可靠权益开通（商业闭环）

**目标**：Portal用户选择真正付费SKU，在**唯一BASE权益槽位提前占用成功后**创建订单→模拟支付→异步可靠开通指定API下个/本UTC月权益；订单、权益和失败状态均可查。复用A权益服务，不通过`/admin/subscriptions`间接开通。详细字段/状态/竞态见[交易与抢购蓝图](../design/v12-commerce-order-promotion-blueprint.md)。

| 子段 | 范围与关键文件/表 | 强制验收 |
|---|---|---|
| B0库存外订单 | `fg_order`、`fg_payment_record`、`fg_order_idempotency`（若订单唯一键不够则独立表），订单快照/价格整数分/可售时间/period，`OrderService` | 同requestId同参只一单、换参数409，跨租户不可见，后端不信任价格 |
| B1支付与超时 | `OrderStateMachine`、`SimulatedPaymentService`、`OrderTimeoutCloser`；支付/取消基于同order行CAS、数据库UTC | 并发付款和取消只能一赢，无重复收据，无超时订单被付款 |
| B2权益异步 | 新独立`fg_business_outbox`、业务Kafka Topic和`fg_business_event_consumed`；`OrderFulfillmentWorker`重用A的GrantService；事务提交后ACK | 进程停于DB提交前/后、Broker停机、100次重放，不重复开通；已付款未开通有明确PENDING和受控重试 |
| B3前端/运营 | Portal购物/确认/订单列表/状态追踪/订阅结果；Console订单及失败事件只读/ADMIN重排/审计，中文帮助 | 空态/失败/超时/重试，1280/375真实浏览器两身份隔离，充值/付款不冒充配额立即生效 |

**MVP缩减**：不接支付宝/微信，不做用户付款记录原文泄露、不自动续费、不支持已消耗权益退款、不做活动库存与优惠券；只模拟支付，明确UI文案。价格/购买日期不能在支付后改变。允许普通非活动SKU价为0时按`FREE_TRIAL`流程，不伪造第三方支付成功。

**阶段终点**：普通用户完成“选商品→创建订单→模拟支付→等待权益→APP调用→余额”闭环，且模拟交易具有真实状态机和恢复机制。B通过后才开始大规模抢购。

## V12-C：限时9.9元/100份抢购与可靠恢复（核心高并发）

**目标**：真实MySQL 100份库存下，高并发入口先由Redis Lua挡绝大多数失败候选；被接受的请求先持唯一BASE槽并写持久purchaseIntent+reserved+Outbox才回**202**；业务Kafka异步创单，模拟支付后成交/开通；对永久未创单Intent有超时回收，对用户丢失202响应有本人可信查回能力，停止Broker/Control/Redis与消费者重复时可恢复、不可超卖。

| 子段 | 范围与实现位置 | 必须通过的门槛 |
|---|---|---|
| C0活动定义 | `fg_promotion`、`fg_promo_stock`，活动时间/版本/限购/退款与库存口径，Console管理页 | 活动SKU冻结、库存数约束、无资格不可下单 |
| C1 Lua预筛+真实持久受理 | `static lua/claim.lua`（服务资源可改命名）、`PromotionAdmissionService`；Redis TIME+库存+一人一单；`UPDATE stock ... reserved+sold<total`、intent与Outbox同MySQL事务 | Redis成功DB失败不202、APP崩在Lua与DB之间TTL重建、双Control限购和DB库存条件更新、状态与活动epoch |
| C2订单Worker | `PromotionOrderConsumer`订阅已有业务Topic、唯一持久intent和唯一order关联，消费后Ack；`IntentTimeoutCloser`回收超过创单截止仍未成单的已受理Intent | 202不是已买；Kafka停恢复/重复/乱序，过期受理只释放一次reserved/HELD，终态不复活 |
| C3支付/取消与Redis补偿 | 复用B的支付/CAS关闭，`reserved--,sold++`或`reserved--`唯一事务；`StockRebuildService`校验DB/Redis差异，带epoch重建 | MySQL sold+reserved不超过total；支付vs关闭仅一赢；补偿重复/Redis失忆不超卖；无坏账/孤儿intent |
| C4运营/体验 | Portal特价详情、活动倒计时（显示用，后端时钟权威）、排队/待付/已开通状态；Console真实活动剩余/持久受理/订单/权益/失败重排与原因 | 交互不混淆四种结果，管理页不暴露Redis临时余额为权威 |
| C5可选优惠券 | **只有C0～C4真实稳定后另行确认**。先单一固定价券/一次使用/到期，不做叠加复杂满减；券核销应绑定订单行状态且取消能回退 | 有真实促销需求再开发，否则延至V12-D之后 |

**压测要求（不是预设成绩）**：隔离两Control/双Gateway/多消费者，目标100份至少一次2万并发模拟申请（逐步升压），核对Redis候选/DB受理/已付款/关闭/未开通和补偿关系；记录排队数、请求受理P95、最终成交、售罄误判、重复申请、Redis/Caps与数据库写入速率、Kafka延迟。不可将HTTP 202算成订单创建或支付成功的吞吐。故障注入要证明Redis丢状态后的DB重建收敛，允许有界少卖而绝不超卖。

**阶段终点**：两种演示：普通SKU购买完整闭环和活动SKU抢购完整闭环并存；原V11 Gateway配额/限流与C统计回归通过。核心抢购完成前不做优惠券拓展。

## V12-D：产品质量、缓存优化、性能与最终验收

**原则**：不再堆新业务。先人工确认Portal与Console“真正给用户用”的可用性，再对实际市场查询/订单和计量链路做性能及索引优化，最后统一交付。

1. **D0产品体验**：Portal从首页到免费领取/付款/活动/应用/Key/调试/调用历史的真实1280px/375px体验，空态、等待态、错误恢复、键盘、Screen Reader基础标签；Console在现有七域基础上按真实运营任务整理，不能堆口号/假卡片。
2. **D1读路径/Redis缓存**：以真实P95/DB热点样本判断哪些商品详情值得Cache Aside、热点/防穿透是否需要null TTL+单flight；写后失效和版本一致性、缓存故障降级；热门排序基于真实授权/浏览采样，不伪造排名。
3. **D2旧V11性能债与新业务分开**：明确执行旧`docs/progress/v11-d-acceptance.md`所列10万级Ledger/Outbox/对账SQL `EXPLAIN ANALYZE`与四组DIRECT/PROXY/GOVERNED/METERED公平实验，记录**V11原始基线**。新增V12 API单品权益的quotaHop/订阅读/市场搜索/支付/秒杀性能，不能混为“无开销”；统一CPU/RSS、P50/P95/P99、成功RPS/429/503/CLIENT_SHED、持久意图转订单和最终权益延迟。
4. **D3故障/权限/迁移**：旧Caller与APP Token/Portal Token/ADMIN隔离；历史V11-A/B/C迁移只在用户授权且可回滚的维护窗口；实际多网关/多Control/Kafka断联、Redis丢状态、订单vs退款竞态、支付与权益恢复；手动操作验收及未修限制。
5. **D4交付**：新README/Portal与Console手册、真实环境启动/诊断、隔离Compose、SQL迁移/预检、OpenAPI接口示例、演示用匿名用户与SKU数据、简历真实数据来源、脱敏证据、独立安全核查和Git stacked PR收口建议。所有PR继续Draft，只有负责人逐项核实并明确授权才可能合并。

**旧E6**必须由负责人亲自签字或在V12-0明确记录延期；自动Chromium不等于真人体验。

## 每个正式阶段统一强制开发合同（未来Codex文件的固定模板）

未来每个阶段**另建一个完整但有限的**`docs/progress/v12-[a|b|c|d]-*.md`任务书以及对应`CODEX_V12_[A|B|C|D]_*.md`，在主线审核阶段设计后单独生成，严禁本轮一次性派发全部代码。任务书必须列出：

1. 业务边界/状态机/禁止事项，旧V11不可破坏的事实、角色及所有权；明确`NOT_READY`开关和旧版本混合保护。
2. 真正的SQL增量表、字段索引、CHECK/UNIQUE、事务/锁顺序、幂等/重试/异常处理；不允许随意DROP/TRUNCATE用户数据。
3. REST API输入/错误码/权限/分页、快照字段、唯一幂等键、异步202/付款/权益分离。
4. 具体新增Java类及文件路径、重要中文注释`//1/2/3`、Maven模块/依赖改动禁止项。
5. **Portal页面、Console页面、中文使用指南必须与每段后端一同更新**；只在真API基础上展示状态，不造假热榜、假销量、假余额。
6. 对应JUnit/Node/隔离MySQL8.4/Redis7/Kafka4.1.1与真实HTTP两节点的通过门槛、浏览器人工待办、故障注入步骤，真实SHA+退出码，失败历史保留，脱敏JSON入新独立文件。
7. Conventional Commits、小步阶段PR、同父stacked分支、只更新当前功能分支，不合并main，不更改旧PR base，不reset-hard/clean-fd/force。
8. 根`.env`、用户旧MySQL/Redis/Kafka数据卷、IDE/JVM、历史Ledger/Outbox，未经明确授权一律只读；测试必须用随机项目/隔离数据，完成后停机并保留需保存的证据。

## 最后完成标准（只有真实执行后才能勾选）

| 场景 | 交付证据 |
|---|---|
| 自助免费API | 真Portal注册/创建APP、免费权益唯一、APP Key经过真实Gateway授权、读到正确V12余额 |
| 自助普通购买 | 真订单→模拟付款→Kafka消费→权益`FULFILLED`→新API配额准入，原同月V11余额完全不变 |
| 抢购并发 | 真实Redis Lua/双Control/DB限制；MySQL`sold+reserved<=total`、一人一单、崩溃与重复消息后可恢复 |
| 额度与安全 | 双Gateway无双扣、旧Caller/APP/Portal/Admin互不混用、每个ID所有权、Kafka异步统计不影响准入 |
| 故障恢复 | Redis状态丢失、Kafka停恢复、DB短故障、付款/取消竞态、权益开通失败重试无重单/重复Grant |
| 运营与使用 | 1280/375真人Portal/Console可独立完成，订单与用量能解释错误、状态真实、无敏感泄露 |
| 工程可复现 | D旧/新各自性能实验、10万级SQL、Java/Node/Python/浏览器实证、启动手册、已知限制、PR审核 |

**此文档不得被解释为V12-A/B/C/D已完成；现有功能仓库尚无这些新Java/SQL/Portal代码。**


## 2026-11-10 消息队列与V12-A远端实装状态同步

V12-A独立功能分支`feat/v12-a-portal`已远端提交自助Portal、商品免费权益、V12 APP/API月配额、独立**RabbitMQ**用量链路与定向证据，见[实施记录](v12-a-implementation.md)和[首次复审](../reviews/v12-a-rabbitmq-static-review-2026-11-10.md)。A0～A6代码和部分真实测试已交付，A7有三项P1待修及负责人手测；**不能标成整阶段正式验收通过**，当前没开始B/C代码。

**队列选择最终裁决**：V12新业务消息（A用量、B订单付款/权益、C抢购Intent/库存补偿）采用RabbitMQ独立exchange/quorum main/retry/poison队列，保留MySQL Outbox+幂等事务；**旧V11-C Kafka保留不迁移**。此前本路线及交易蓝图里提到未来V12 Kafka Consumer/Topic的文字以[消息适配设计](../design/v12-rabbitmq-adaptation.md)与ADR-13为准。B/C的阶段开发合同必须写Rabbit的publisher confirm、mandatory returns、COMMIT后basicAck、持久重试/隔离和实际容器故障测试。
