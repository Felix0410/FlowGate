# FlowGate V1.2｜现有代码、远端分支与交付证据审计（2026-11-10）

> **性质：只读审计结论，不是V1.2实现/测试报告。** 本轮基于GitHub真实远端分支、PR #1～#13、与最新功能链`feat/v11-e-local-delivery@b7f2a88794a6936d1e9854408e27d6f1f5387915`的Java/SQL/JS/Compose/实施进度/脱敏证据进行核对。没有在本轮新运行用户本机Java、MySQL、Redis、Kafka、浏览器或性能实验，不替负责人签署E6。

## 1. 分支/PR是真实什么状态

- `main@84a25d5a7b9545dff3556299efec3c310fc321cf`仍为早期Block0基线；**不能基于main README判断新业务未实现**。
- 实际堆叠包含`test/block4-final-acceptance`→`docs/v1.1-openapi-platform`→`feat/v11-a-business`→`feat/v11-b-quota`→`feat/v11-c-async-usage`→`feat/v11-console-unslop`→`docs/v11-console-workbench-redesign`→`feat/v11-console-workbench`→`docs/v11-e-local-delivery-closeout`→`feat/v11-e-local-delivery`。GitHub远端比较显示C至E这段后继关系`ahead=21,behind=0`，D不在祖先链中。
- 13个远端PR均`open/draft`、未合并。PR #10是初次前端清理；#11是7业务域/20能力重构；#12是E设计；#13是E代码。**V11-D无实现分支/PR**，仅有`docs/progress/v11-d-acceptance.md`的D0～D4任务设计。
- 本次V1.2只新建**文档提案分支**`docs/v12-platform-business-redesign`，基于最新E代码；不修改旧PR的base、不合并、不中途开展D性能或V12编码。

## 2. 每阶段成熟度：必须区分代码/自动证据/真人/正式交付

| 领域/阶段 | 真实代码依据 | 已提交证据 | 尚未完成 |
|---|---|---|---|
| V1基础 | `flowgate-control`、`gateway`、`demo`、`contract`：目录/发布/Caller、路由代理、Redis Lua限流/在途并发、超时/熔断 | Block1～4历史真实联调/隔离测试文件 | 旧V1跨阶段最终人工签字、PR合并 |
| V11-A | `fg_developer`、`fg_application`、`fg_api_plan`、`fg_subscription`、`DeveloperApplicationService`、`PlanSubscriptionService` | A阶段定向MySQL/HTTP证据，后续E业务回归 | 目前只有ADMIN管理接口；**普通用户无自助注册/购买** |
| V11-B | 独立`APP_CALLER`、`AppCredentialService`、`QuotaChargeService`；`fg_quota_balance`、`fg_quota_ledger`、`fg_outbox_event` | V11-B复审/C0真库；E再跑原B23项 | 缺API单品套餐、订单支付、Portal访问权限；V11原库无自动迁移 |
| V11-C | Kafka Worker/消费去重/日聚合/对账与三项复审修复 | C复审70项Java、新13组真故障、原26组回归；E再跑原C26组 | Kafka单节点不是HA；10万级JSON关联和索引性能未测 |
| V11-D | `docs/progress/v11-d-acceptance.md`有D0～D4详细合同 | **没有D正式执行证据/新代码** | 10万级SQL、四组公平压测、正式故障/验收结果 |
| UX 重构 | `static/console/navigation.js`七域20叶子页、深链接/详情/共享报表及工作台 | `console-workbench.json`、1280/375px真实自动化浏览器截图与Node | **负责人真人体验签字未完成** |
| V11-E | `scripts/v11-local.py` doctor/up/status/stop、隔离Compose、后台readiness与交付指南 | `v11-e-local-delivery.json`: JDK17 Java**75项**失败0跳过0、**9**个Node入口、Python**14**项；B**23**、C**26**真实隔离回归；E隔离V0→V1→V2后Free5为5个200+1个429、余额/流水/异步均5，昨天封账MATCH；真实Chromium1280/375自动检查 | **E6负责人手测NOT_EXECUTED**；用户旧持久库未迁移/未授权激活，不代表常驻环境已上线 |

这些通过数均是**Codex提交的实际运行报告和脱敏JSON**；ChatGPT当前只核对远端代码、脚本与结果的关联，未独立重跑。

## 3. 从SQL和关键服务确认的不可突破现状

1. **现有套餐不是可按API售卖的商品**。表`fg_api_plan`只有code/name/monthly_units/plan_version/status，没有价格、API归属、商品上下架等商业字段；`fg_subscription`以`application_id+plan_id+UTC期间`定义，`PlanSubscriptionService.subscribe()`先锁应用再按同月唯一校验，`SubscriptionSchedule`禁止历史月回填、未来只允许下一UTC自然月。
2. **现有配额是应用整月唯一池**。`fg_quota_balance`唯一键`(application_id,period_yyyymm)`，首次Grant冻结`subscription_id/plan_id/plan_version/total_units`；`QuotaChargeService.requireFrozenBalance`验证冻结不可变，同月多订阅拒绝，`fg_quota_ledger.charge_id`唯一、余额+Ledger+Outbox在同MySQL事务。不能给某API或订单直接更新旧月额度，也不能靠同月“再订购”换新套餐。
3. **V11已有强边界**。`IdentityService`管理ADMIN和旧Caller；APP凭证独立`fg:auth:app:`和代次；`CallerAuthFilter`按是否计量区分APP与旧Caller并校验Scope。旧管理`/admin/**`采用ADMIN/OPERATOR/VIEWER；`AdminAuthInterceptor.v11Path`使V11业务写操作仅ADMIN。现有前端未拥有第三方用户主体和租户归属ACL。
4. **账本不是收费账本**。V11 Ledger记录“准入授权单位”，不是购买金额或支付记录；下游失败不退款。`fg_outbox_event`现服务USAGE_GRANTED，C消费者按严格格式核对DB Ledger后做日聚合，不能拿它当订单MQ直接塞任意payload而不新增类型分发和测试。
5. **V11-E默认安全关闭**。B配额激活要求完整可信接流节点/心跳/版本/checksum；C Async默认OFF。隔离演示成功不等于用户常驻Kafka、MySQL已迁移或开放公网。
6. **后台页面不是门户**。最新`navigation.js`七域20叶子页全为内部运营和配置，前端`/console/`使用管理会话；`/portal/`、用户订单/支付/活动/优惠券/市场等源码及数据库结构在递归树中均不存在。

目前最新E分支Git树共约400个路径，SQL仅`flowgate-init.sql`、`flowgate-v11.sql`、`flowgate-v11-c.sql`及只读预检；没有V12 SQL/订单/Portal代码。**所有V1.2内容均为待审设计，绝不能写“已实现”。**

## 4. V1.2前置阻断与决策

| 级别 | 需解决事项 | 原因/入门门槛 |
|---|---|---|
| P0 | **确立V12权益模型、Legacy/V12计量选择规则** | V11唯一App/月余额与新按API订阅语义不同；无隔离会越权消耗其他API/重复扣额 |
| P0 | **订单/支付/权益原子和可重试状态合同** | 禁止用户点击购买后直接创建V11订阅；避免已付未开通、关闭后支付、重复授权 |
| P0 | **Portal登录与租户所有权鉴权** | 现有所有管理接口只认ADMIN会话；把原`/admin`前端隐藏不足以阻止跨用户访问 |
| P0 | **活动Redis预扣到持久受理之间的崩溃窗口定义** | 跨Redis/MySQL/Kafka没有全局事务；不能把Lua预扣当做购买成功或返回不可恢复的202 |
| P0 | **E6手动体验+迁移策略/安全基线** | 现有20功能用户仍未亲自体验；不能在其用户持久库上自动迁移；建议V12-0先保留一次稳定手测和完整分支基线 |
| P1 | **保留V11-D性能/SQL独立证据任务** | 不能把E业务闭环或旧V1压测冒充V11计量四组性能；V12新增压力与旧D应区分 |
| P1 | **Portal与Console可维护的前端边界** | 独立URL/鉴权/导航/权限/静态构建；共享最小UI原语，不共享ADMIN控制器 |
| P2 | 缓存热榜、收藏、优惠券复杂规则 | 可做后续可验证优化，但不应阻塞首个可支付订阅闭环 |

## 5. 上游HMDP Plus的实际代码复核（冻结到已有审计基准）

读取了`java-up-up/hmdp-plus@03594d190275a32d151e5d8b45da24c0f4efbe14`的`hmdp-core-service/src/main/resources/lua/seckillVoucher.lua`、`seckillVoucherRollBack.lua`、`VoucherOrderServiceImpl`、`SeckillVoucherServiceImpl`、`kafka/consumer/SeckillVoucherConsumer.java`、`CacheClient`和pom：

- **思想可借鉴**：Lua使用Redis TIME、校验活动时段/状态/库存/用户已购，原子扣减库存与资格集合并记录trace；订单侧异步/幂等与补偿状态；热点缓存和缓存空值；Kafka消费域隔离。
- **不直接拷贝**：Plus是Spring Boot **3.5.4**、MyBatis Boot3 Starter及大量自建模块；FlowGate当前Java17/Boot4/原生ESM与已有Kafka/Outbox。Plus旧秒杀/回滚与多种异步实现需要根据现存事务边界逐一验证，不是可直接粘贴的生产证明。
- **具体审计细节**：参考回滚Lua包含`DEL stockKey`和外部修复路线，不能把“反向INCR库存”当作无需持久仲裁的完整答案；本项目必须以MySQL库存预留和唯一订单约束兜底。缓存模板里也存在阻塞递归重试/简单DEL锁等教学式风险，不能放入Gateway Netty事件循环或原样移植。
- **不引入**：商铺/点评/GEO/签到、通用分库分表、大型Redisson框架、复杂布隆过滤器/延迟队列及各种重复抽象。排名可待市场确有访问量时用Redis ZSet，不要提前写假热榜。

代码引用：`https://github.com/java-up-up/hmdp-plus/tree/03594d190275a32d151e5d8b45da24c0f4efbe14`。

## 6. 最终审计结论

**V11功能线A/B/C+UX+E0～E5真实已编码并有各自自动测试证据；D性能/综合正式验收未实施，E6负责人真人手测未执行，旧PR仍Draft/main旧。V1.2用户、商品API市场、订单、支付、抢购、优惠券与Portal一律属于新设计。** 推荐以`feat/v11-e-local-delivery`作为后续V12开发**候选基线**，待负责人确认设计后另建`feat/v12-a-portal`等阶段分支；设计只在`docs/v12-platform-business-redesign`分支进行，不影响原功能线。
