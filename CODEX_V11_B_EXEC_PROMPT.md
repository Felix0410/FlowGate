# Codex执行提示｜FlowGate V11-B：A遗留修复 + 可信计量准入 + MySQL权威配额

> **2026-11-07 最新负责人决定**：V11-A复审发现的两项P0**并入V11-B B0完成**，不再单开A修复任务。本文件是当前本地Codex唯一可执行B阶段任务；先B0独立验证，再B1/B2/B3/B4/B5/B6按顺序做。V11-B**目前只有设计文件，尚未开始真实编码/测试**。不要把设计合同标记为已交付。
>
> **唯一架构/业务合同** `docs/design/v11-b-quota-execution.md`；**唯一阶段任务单** `docs/progress/v11-b-quota.md` B-07～B-15。前置完整业务源码在`feat/v11-a-business`，B分支`feat/v11-b-quota`从该A源码分出；PR保持Draft，main/旧PR不动。

## 0. 先安全核对，保护用户环境

1. 命令：`pwd; git status -sb; git branch -vv; git remote -v; git fetch origin; git log -1 --oneline`。本地若未跟踪/未提交文件、已有同名分支，先核对SHA与实际工作树；**不`reset --hard`、`git clean -fd`、强推、改写Git历史/远端PR base**。
2. 无本地分支时`git switch --track origin/feat/v11-b-quota`；已有分支则安全切换，不自行从旧main建B。
3. 检查JDK17/Maven、原IDE/Control/Gateway/JVM、MySQL/Redis容器与数据卷，**只使用隔离Lab和专用MySQL8.4/Redis7随机端口**。不改用户`.env`、不删除/停机用户已有持久卷、Docker prune或迁移用户真实数据。
4. 按序阅读 `AGENTS.md` → `docs/design/v11-b-quota-execution.md`全文 → `docs/progress/v11-b-quota.md` B-07～B-15 → `docs/design/openapi-platform-v1.1.md`第12节 → `docs/reviews/v11-a-static-review-2026-11-07.md` → 实际源码：`SubscriptionSchedule`、`PlanSubscriptionService`、`ApplicationEntryMapper`、Control JDBC配置、`IdentityService`、`CallerAuthFilter`、`AdmissionGlobalFilter`、`PolicyRules`、`EffectivePolicyResolver`、`SnapshotRouteDefinitionLocator`、`ReleaseService/ReleaseController`、`SnapshotPollingService`、`/console/` ES Modules。
5. **只改B范围**；无框架重选型、订单/支付、Kafka Broker/Producer、Seata/Redis分布式锁/新Web框架。Java/JS关键长流程中文注释按`// 1.` `// 2.` `// 3.`。

## 1. B0：必须先修完并独立通过的A遗留P0

**B0-1 UTC月度套餐锁定**：在`SubscriptionSchedule.validate`拒绝同App本月已结束但仍占当月的Free订阅后，再当月建立Pro，即使区间不重叠；`2026-11-20`已存在`[11/1,11/10)`，请求`[11/11,12/1)`须409。允许下一UTC月首日相接，保留应用行锁与审计同事务；本版每条订阅限单个UTC计费月，补纯规则及隔离真实MySQL测试。

**B0-2 MySQL连接池强制UTC**：`connectionTimeZone=UTC`不保证服务端`@@session.time_zone`，对Hikari连接设安全的`SET time_zone='+00:00'`初始化（或等价有证据方法），检验`ApplicationEntryMapper.nowUtc`、`TIMESTAMP(3)`和`Instant`映射；非UTC server时区/JVM时区+UTC月底毫秒必须实测、旧V1发布/节点/审计不回归。**不要修改数据库全局时区或用户真实库**。

**B0单独结束条件**：JDK17 Maven受影响测试/Node/隔离MySQL真实HTTP，保留旧`v11-a-smoke.json`，新增`docs/progress/evidence/v11-b0-smoke.json`（必须真实运行才写）。以`fix(v11-b): close monthly subscription and utc session gaps`提交，记录实际SHA、测试数字、初始错误与最终状态。**B0失败就停止，不能继续B1或暗中发App Token。**

## 2. B1：配额协议/节点能力/发布兼容

按B设计4与任务B-09：
- `USAGE_QUOTA`类型只在private启用API/Route生效，`unitsPerCall 1..100`；同一路由实际只允许一个quota，旧四类执行语义不变。
- 独立`ENFORCED_QUOTA_V1`与`QuotaPlan` metadata，Route同版冻结`apiId/routeId/policyId/units/appliedVersion/checksum`；**不能把Quota加入旧`EffectivePolicyResolver.Rule`容量参数推导导致NPE**，不改历史Snapshot与checksum。
- 网关节点上报`USAGE_QUOTA_V1`可信capability；Control显式读取部署配置`FLOWGATE_QUOTA_TRAFFIC_NODES`接流节点名单（默认空不能激活）；任何缺失/旧Gateway/STALE/能力未知→拒绝发布包含Quota的配置。回滚到含Quota的历史也验证；回滚到非Quota旧版无此门槛。
- 单旧+单新Gateway能力拒绝、双新允许、过期心跳拒绝、旧快照回滚/旧Caller兼容真实联调；定向Java测试后提交`feat(v11-b): guard metered publishes by gateway capabilities`。

## 3. B2：应用凭证身份域，只先做安全内部实现

按B设计5与任务B-10：
- 新Token格式`fgapp_`+43位Base64URL，Redis仅`fg:auth:app:{digest}`和`fg:auth:app-index:{id}`，DB主体`principal_type/role=APP_CALLER`；旧V1普通Caller43位及`fg:auth:caller:`完全保留。
- 新`fg_app_credential_state`持久化`generation`和enabled，绑定既有`fg_app_principal`；签发/轮换/撤销先提交DB代次，再Redis Lua原子会话替换，只有ACK后一次性展示Token。DB/Redis不跨库原子，故即使Redis删除失败，Quota事务也必须拒绝被撤销的旧generation。
- B2开发过程`POST /admin/apps/{id}/credentials`**仍保持对外409 FEATURE_NOT_READY**直到B4/B3安全闭环通过；先通过服务定向测试/受控内部Lab验证B2功能，不提前启动面向用户的活跃Token功能。真实签发开放与app-only路由检查必须同版本交付。
- Gateway仅计量私有路由接APP Token，旧普通Caller不能从计量路由绕开扣额；非计量私有路由不接受APP Token，旧Gateway不接受APP格式。测试双管理员并发、轮换、Redis异常、DB撤销、父级停用、旧Caller无回归。
- 提交`feat(v11-b): isolate application credentials and generations`。

## 4. B3：MySQL权威配额交易和可信内部服务

按B设计6与任务B-11：
- 增量SQL仅CREATE IF NOT EXISTS，新增`fg_quota_balance/fg_quota_ledger/fg_outbox_event`（与B1能力表+B2凭证状态表一共五张）；Outbox预留`lease_owner/lease_until/last_error_code`供C后续使用，但**本块不连Kafka**。
- `POST /internal/quota/charge`独立于Admin操作，仅Internal Key/本机可信网络；`chargeId,principalId,credentialGeneration,apiId,routeId,appliedVersion,snapshotChecksum,units`。不信任用户的AppId、Header、Body，Control必须读取对应历史ConfigRelease二次核对私有Route和真实USAGE_QUOTA绑定，而非仅核对当前版本。
- 同一MySQL事务：DB权威当前UTC/订阅/APP+Developer+Scope+generation→创建或读取唯一Balance并冻结订阅版本与单位→`charge_id UNIQUE` Ledger插入→条件扣减余额→Outbox NEW插入→commit才授权；不超额、不产生孤立Ledger/Outbox。
- 对重复chargeId的Unique竞争，**回滚冲突事务后新事务读取历史**，匹配返回duplicate不再扣，payload变更409；跨UTC月仍返回历史冻结月份，不在新月重扣；若当前App或Token已禁用则403不允许继续代理，保留旧Ledger事实。
- 并发100次Free5只5笔唯一授权、余额0/Ledger5/Outbox5；相同chargeId并发100只扣1；Control/MySQL故障fail-closed，旧未计量路由不受影响。
- 提交`feat(v11-b): add transactional quota ledger and outbox`。

## 5. B4：WebFlux准入与filter顺序阻断

按B设计7与任务B-12：
- 现有`CallerAuthFilter=-100`、`AdmissionGlobalFilter=-90`继续执行，新增`QuotaAdmissionGlobalFilter` **候选+10，必须通过当前5.0.x Gateway组合Filter真实输出和CB OPEN测试确认**。意图在Route CB短路后、NettyRoutingFilter之前、并发许可内侧。
- WebClient异步有界timeout/并发与**同一chargeId**有限重试；不`block()`/JDBC/同步Redis、不自动重试下游POST；内部无响应或不确定一律503 QUOTA_UNAVAILABLE。成功Quota Grant后下游500/超时/cancel不退款；原Rate/Concurrency拒绝不扣额，许可doFinally归还。
- 如果CB-OPEN前置不能在不破坏V1下证明，**计量+熔断组合配置先被发布侧拒绝**，或先报告负责人决定是否接受真实“Grant即扣”的竞态语义，绝不以文档掩盖实验失败。
- **B3+B4+B1均通过后**才解除B2对外A阶段凭证409，实际签发/计量同版本上线，不允许中间不受保护阶段。提交`feat(v11-b): enforce nonblocking quota admission`。

## 6. B5：产品页面与指南

- `/console/`同步真实应用凭证状态/一次性显示、轮换吊销确认、当前月份余额、Ledger游标、计量规则绑定/发布、节点实际能力状态、Quota429/503等中文错误解释；订阅日期改业务选项。本期Outbox只NEW待投递，不画虚假Kafka聚合图。
- 仅ADMIN写新开发者/应用/套餐/订阅/凭证；OPERATOR/VIEWER只读。旧服务/API/Route/governance/monitoring页面不回归，`/test-console/`只做开发实验。
- 指南源文档与生成的`/console/guide.html`同步，Node新产品页契约、1280px/375px真人体验仍待负责人。提交`feat(console): support metered api and quota management`。

## 7. B6：定向验证、真实结果与退出标准

- JDK17 Maven受影响模块Test/Package；Node新增`scripts/v11-b-console-test.mjs`+原V11-A、旧V1产品测试；Python隔离`scripts/v11-b-smoke.py`需要2 Gateway、Control、MySQL8.4/Redis7，固定时钟、CB OPEN、断Control/DB、故障响应丢失、混合旧Gateway。
- 并发100次额度5、重复chargeId100次、跨月重复不扣、不同payload409、DB失联Quota503且旧路由成功、Redis限流不扣月额度、OLD snapshot回滚、Token撤销/停用/旧V1 Caller回归**必须真实实测**。
- 真实命令/退出码/失败/跳过/修复历史、完整SHA、Node/HTTP数量、数据库守恒/出入账摘要写`docs/progress/v11-b-quota.md`的实施后续节。仓库证据仅脱敏，旧A 155条历史证据不覆盖。全部功能的产品指南更新后才标B技术完成。
- **当前只授权按任务合同编码B；完成后停止等待ChatGPT复审与负责人真人浏览器验收，不自行编码C/Kafka或D/压测，不合并任何PR/main。**

## 8. 固定输出格式

请回复：当前分支与SHA、B0/B1/.../B6各步已完成和待办、实际文件与SQL/API、Java/Node/SQL/HTTP命令与退出码、关键真实断言数量、是否发生混合节点泄漏/跨月重复扣减、已知限制、当前PR推送成功/失败原因和负责人需要手测的入口。无现场实测的步骤明确标未执行，不能用设计文档或旧A证据充数。
