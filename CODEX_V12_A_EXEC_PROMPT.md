> **实施补充授权（2026-11-10）**：负责人选择V12改用RabbitMQ、V11保留Kafka；功能优先，只做必要定向验证。见ADR-13，本文中V12 Kafka专线要求由RabbitMQ专线取代。未执行的大矩阵/人工验收必须如实列出。

# 给本地Codex的唯一执行提示：FlowGate V12-A（Portal+API市场+免费API权益）

> **这是供负责人确认后交给Codex的实施合同，当前GitHub PR #14只含设计文档，未自动启动Codex和任何业务编码。**
>
> **V12-A唯一目标**：真实普通Portal用户自行注册、发现实际发布的API、创建自己的APP、领取一次免费**APP/API/UTC月**权益、签发B独立APP Token，经Gateway真实5次200/第6次429，并看到正确权威余额/流水；旧V11 B/C业务保持正常。**没有订单、模拟支付、抢购、优惠券。**

## 必须先完整阅读（从上到下）

1. 根`AGENTS.md`只看现有工程安全、提交规范；其中旧C/E“当前执行任务”属于历史，不得重复执行旧合同。
2. `docs/reviews/v12-current-state-audit-2026-11-10.md`与`docs/design/v12-architecture-decisions.md`：**源代码事实及最新决策优先**。
3. `docs/design/flowgate-v1.2-business-architecture.md`、`docs/design/v12-transaction-invariants.md`：原总体流程；出现差异以新ADR和A合同为准。
4. **唯一当前完整实现规范**：`docs/progress/v12-a-portal-free-subscription.md`，按A-00～A-08全部章节逐段完成；`docs/progress/v12-roadmap.md`只作为阶段总目录。
5. 原`flowgate-control`的`IdentityService`、`DeveloperApplicationService`、`PlanSubscriptionService`、`AppCredentialService`、`QuotaChargeService`、`UsageAggregationService`、`OutboxPublisher`及`AdminAuthInterceptor`；`flowgate-contract`的`PolicyRules`、`QuotaPolicy`；`gateway`的`CallerAuthFilter`、`QuotaAdmissionGlobalFilter`、`SnapshotRouteDefinitionLocator`；`scripts/v11-local.py`与E真实隔离测试；当前Console导航和产品指南。

## 不可突破的执行边界

- 先验证远端GitHub`docs/v12-platform-business-redesign`最新HEAD包含本提示/全部ADR；`git status -sb`检查用户未提交工作；从**最新设计分支**新建`feat/v12-a-portal`，不从main、C或旧E的早期SHA开工；PR base只能是直接父设计分支，保持Draft。
- **不要对用户.env/IDE进程/MySQL/Redis/Kafka容器或旧卷做任何写入、停启或迁移。** 真实测试只使用你自己新建、随机Compose项目/端口/数据库/卷和不可变测试Jar；不可`down -v`、`DROP/TRUNCATE`用户库、`git reset --hard`、`git clean -fd`、强推、rebase共享分支、自动merge main。不能把E已创建旧隔离卷当成新测试资源直接清除。
- 继续Java17+Boot4+Gateway WebFlux非阻塞+Control MVC/MyBatis-Plus+MySQL8.4/Redis7 Lua/Kafka4.1.1+原生ES Modules；不引入Plus原源码整套依赖、Nacos/Seata/新微服务/前端npm框架。重要类、长方法以准确中文注释，复杂流程`// 1.`、`// 2.`、`// 3.`。
- `FLOWGATE_PORTAL_ENABLED`、`FLOWGATE_V12_QUOTA_ENABLED`安全默认OFF；所有门户新表/新Kafka只在授权的**隔离测试环境**打开，旧V1/11 OFF回归不查新表，混合版本旧Gateway不得绕过。
- **V11老表永久保持原语义**：同APP/月旧冻结、旧配额Ledger/Outbox/C日报、旧Caller/APP Token互不混用、原ADMIN权限和发布校验保持；新V12仅APP/API/月，旧新同一route不能并存/双扣，不得复用老`fg_usage_daily`记录V12事件。
- **B/C停工线**：不新增订单、付款、活动、优惠券表/模拟状态，不开展旧V11-D正式10万SQL与四组长期压测。新增A阶段必要的数据库和页面全部要有真实来源。Portal普通用户不填内部ID/JSON，不能直接把`/admin/**`请求从用户前端转发。

## 固定开发顺序：每一步完成/测试/提交/同步前端与指南后再进入下一步

### A0 安全基线与DDL可运行评估
- 核对当前分支树、实际Java17、旧B/C、E基线与真实数据库结构；记录独立测试环境策略。对`docs/progress/v12-a-portal-free-subscription.md`中的14张新表逐一确认外键、索引、CHECK及迁移幂等可运行。
- 单独创建`sql/flowgate-v12-a.sql`；在全新随机库执行V1→V11→C→V12A的顺序迁移；再跑一次幂等，无DROP/TRUNCATE。隔离样本与用户库不互通。

### A1 用户认证与工作区归属
- 实现PORTAL_USER主体/资料/DeveloperMember、注册/登录/退出、bcrypt+Redis独立摘要会话、限速和所属检查；服务端不可相信body的用户/owner。
- 完成真实两个Portal用户的会话跨用途401与跨developer/app的404，2个Control同名注册唯一DB事务安全；Portal基础登录/空态同提交。

### A2 真正市场及运营上架
- Console ADMIN配置分类、Listing、FreeBase SKU以及商品上架，商品引用原已经发布且可信V12私有计量API；普通Portal只看到真实上架、类目、详情、有效FreeSKU、稳定分页。
- 商品可见与**可免费领取**分别判状态，未发布/旧节点不提供假成功；SKU不可变额度/价保护，文档XSS安全渲染；更新相应Console页/Portal页与中文帮助。

### A3 应用自助/APP凭证/免费权益
- APP所有权后端复验；统一调用B`AppCredentialService`但不让Portal变管理员，签发/轮换需要B完整可信条件+新V12能力，撤销在OFF仍安全；短期APP Token 3600秒、一次性回显、离页清除。
- 按`fg_api_entitlement_slot`+唯一FreeClaim/Grant/Subscription/Balance原子事务实现`POST /portal/api/free-grants`；同APP/API/月只有一个BASE，用户同API终身一次Free领取，不允许已存在V11旧订阅“换余额”造成覆盖。
- 真实两用户并发、100个同用户请求，只有一个成功写入并有正确冲突码；客户端按API和应用的真实可用商品选择，不填策略ID。

### A4 新版本计量/兼容
- Contract显式`mode=V11_APP|V12_APP_API|NONE`，旧快照按原freeze/Checksum工作；V12`USAGE_QUOTA_V12`只允许私有API/Route且节点Feature完整；发布/回滚/旧二进制fail-closed与Quota激活门禁。
- Gateway原非阻塞QuotaFilter按**可信版本元数据**选一条Control授权路径；内部V12权威事务先读可信身份及权益，然后余额/唯一Ledger/新Outbox同事务；跨月重试、旧Caller误用、下游失败不退款。
- 真正双Gateway通同一个MySQL：每个APP/API Free5五次200、第六次429；旧B23/C26所有原逻辑不变。

### A5 V12 Kafka读模型独立专线
- V12专有`fg_api_usage_outbox`、`fg_api_consumed_event`、`fg_api_usage_daily`及独立Topic/Group；Outbox lease/at-least-once/SENT等待期补投；消费者必须核对本地V12 Ledger、同事务去重/聚合，成功COMMIT后才ACK。
- 异步OFF不建Topic、不宣称真实日报；旧C仍工作；Kafka故障/重复100次/两Listener/离线后恢复/昨天封账对账必须有独立真实测试，不让C旧严格八字段监听消费新消息。
- 应用用量页面明确两个口径：MySQL Ledger当前实时授权与Kafka延迟日报，禁止“尚未统计=0使用量”。

### A6 整体Portal/Console可用性与自动验收
- 1280与375真实Chromium/键盘，普通用户真实注册→搜索→APP→FreeSKU→Token→Gateway→余额/流水；第二用户无法读；多状态错误清晰、没有宣传口号/无意义卡片/假热门。
- 真全模块Java17`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`、原Node9项、V11-B23/C26、A全隔离HTTP/Kafka、多Gateway、Portal Node/真实Chromium及中文指南检查均执行并记录实际退出码。新增`docs/progress/evidence/v12-a-*.json`；不要覆盖任何V11证据。
- 最终把`docs/progress/v12-a-portal-free-subscription.md` A0～A7每项真实状态、失败尝试、修复、代码SHA、方法和未执行人工手测写回；README/Portal中文手册/Console手册与可演示数据同步。

## 提交与停机反馈

- 小步Conventional Commits：`feat(v12-a):...`、`test(v12-a):...`、`docs(v12-a):...`；只推`feat/v12-a-portal`并开**Draft PR base docs/v12-platform-business-redesign**，旧PR #1～#14仍原样。若Git push 403，不强推/不改变旧分支关系；保留本地已验证提交与所有tree SHA，向负责人报告可用的GitHub App交付路径，所有代码与测试证据远端已核实后才称“交付”。
- **做到A6后立即停在A7等待ChatGPT源码复审及负责人浏览器人工验收**。不要擅自进入B普通订单、C抢购、D性能或合并main。
- 交付总结必须明确：已运行/未运行、Java与Node实测数、真实HTTP/Kafka/多网关记录、用户旧资产是否未变、E6与V11-D原有债是否仍待执行。任何未证实证据一律写`NOT_EXECUTED`，不编造性能数。
