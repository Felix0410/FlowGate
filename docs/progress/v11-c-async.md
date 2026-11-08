# FlowGate V1.1-C：Kafka、Outbox、异步用量、对账补偿（Codex 编码合同）

> 状态：未开始，必须在 A/B 真实已实现后开始。本块是 **MySQL 账本 → Outbox → Kafka → 用量读模型**，绝不可将 Kafka 改成配额扣减的唯一事实来源。每条命令和实际测试证据按本块进度末尾回填。

## C-00 业务定义

每条 fg_quota_ledger 表示已授予的累计配额；同一事务已经写有 fg_outbox_event 的 USAGE_GRANTED 事件。异步消费者生成按 app、API、UTC 日期的 fg_usage_daily 汇总，并允许运营人员查看最近用量、事件堆积和对账差异。异步统计可能稍晚，但用户当期**权威余额**永远从 MySQL fg_quota_balance 取，绝不从 Kafka/聚合视图判断能否再次准入。

## C-01 依赖与数据库

1. 仅在 flowgate-control 的 pom.xml 引入与当前 Spring Boot 版本兼容的 Spring for Apache Kafka 依赖；Kafka 单节点仅用于本机 compose.local.yml（增加启动 profile/可选项），不另起一大批 Java 微服务；旧 V1 无 Kafka 时仍可正常启动。先验证实际 BOM 与 Boot4 兼容，不凭记忆编版本号。
2. 表 fg_outbox_event 在 B 块已建立；本块增加 fg_consumed_event(event_id PRIMARY KEY,processed_at)，fg_usage_daily(day_utc,application_id,api_id,units,request_count,updated_at,PRIMARY KEY(day_utc,application_id,api_id))。查询需有时间范围与 app/api 组合索引。
3. 事件结构 version=1、eventId、chargeId、applicationId、apiId、periodUtc、units、eventTime；Kafka topic flowgate.usage.v1，message key 为 appId，禁止透传 Token、HTTP Body、Header、IP 等敏感字段。

## C-02 可靠投递服务

1. Outbox worker 可配置开关、批次上限、并发上限与退避策略。使用 MySQL 的 SELECT ... FOR UPDATE SKIP LOCKED **仅在短事务中领取可投递事件**，立即写 leaseOwner/leaseUntil（如 B 建表未预留，则通过兼容增量 DDL 补）；快速提交事务。不能开一个数据库事务一直持锁等待 Kafka send()。
2. Kafka ack 成功后按 id+leaseOwner 标记 SENT；失败按指数退避更新 next_attempt_at、attempt_count，超过阈值到 FAILED 并暴露可诊断原因码（脱敏）；租约到期可安全重领，不允许永远 IN_PROGRESS。
3. Kafka 可能已收消息、进程却在标记 SENT 前崩溃；因此允许消息重复投递，**绝不承诺 Kafka+MySQL 跨资源 exactly-once**。生产者用合适 ack/retry 设置并在集成测试中模拟此场景。
4. 控制面业务线程不等待 Kafka 完成：计量主事务与本地 Outbox 写入成功即可返回配额授权；Kafka 宕机只影响异步统计，Outbox 堆积应可见且可限流/告警。

## C-03 消费与幂等

1. 消费者收到事件，在**同一个 MySQL 事务**中 INSERT fg_consumed_event(event_id) 与 UPSERT fg_usage_daily 累加 units/count；若 eventId 已存在，跳过累加。
2. 只有业务事务提交后才向 Kafka 提交 offset；已写数据库但 offset 未提交时，消息重放不会再次加总。避免在异常 catch 中直接 ack、避免超过尝试上限静默丢弃。
3. 消费失败带可解释的 Retry/Dead Letter 路径。重放失败事件必须复用原 eventId，防止一次重投产生第二次计数。
4. Consumer 可停机和恢复、重新平衡；自定义线程池/任务并发有上限，不能把阻塞数据库消费交给 Gateway Reactor 事件循环。

## C-04 对账及人工排障

1. 用 fg_quota_ledger 权威 SUM(units) 按 day/app/api 与 fg_usage_daily 对比；在 worker 积压时应把“尚未消费的 Outbox”作为可解释延迟，而非立刻修改余额。
2. 管理端 /admin/usage 支持 app、API、日期区间与游标分页；/admin/async-events 支持 NEW/LEASED/SENT/FAILED 与积压数；/admin/reconciliation 支持指定时间段校验差异与幂等修复读模型。限管理员/运营；VIEWER 只读；所有手工重放记录审计。
3. 校验不得用非事务快照把实时新增 Ledger 和聚合视图相比较而报错；只对关闭的日期区间或固定 watermark 对账，记录 cut-off 时间和 lag。
4. 定向人工排查能够追踪 eventId→chargeId→ledger→daily aggregate，不需要读取应用 Token 或原始 API 数据。

## C-05 定向故障用例

- Kafka 停止：内部扣减仍 200/429、Outbox 增加、统计延迟明确；Kafka 恢复后事件被送达，聚合追平。
- Kafka ack 成功但更新 Outbox SENT 前故意中断：重复发送，fg_consumed_event 只存一次，fg_usage_daily 不重复累加。
- DB 聚合提交后、不提交 Kafka offset 时退出 Consumer：重放后汇总不变。
- 消费者处理失败可重试；不可恢复错误进入 FAILED/DLT，运营端可查看、手动安全重放。
- 双 worker 并发领取、超时租约回收，不重复扣 MySQL 额度、不无界堆积；月末事件日期取 Ledger 冻结 UTC 事件时间。
- 同时验证 2 个应用各 5/20 单位的账本总和与汇总一致，发布事件不携带 Token；未计量请求不依赖 Kafka。
- Node 产品页定向测试 + JDK17 Control 测试 + 隔离 MySQL/Redis/Kafka 真实集成；脱敏证据存 docs/progress/evidence/，执行后回填。

## C-06 不做

不新增 Kafka 到 Gateway 依赖，不在 HTTP 热路径同步等待 Kafka ack，不建设通用订单业务，不把异步用量称为实时权威账单，不用 Redis 的 key TTL 代替 Outbox 幂等/持久化。


## C-07 审核后执行分配（A/B未通过时不得运行）

| 阶段 | 工作内容 | 验收门槛 |
|---|---|---|
| C0 | 从B权威Ledger/Outbox实测中冻结事件Schema V1：eventId/chargeId/appId/apiId/period/units/time/version，脱敏；核对B已预留leaseOwner/leaseUntil | Outbox/ledger同事务事实成立 |
| C1 | Control可选Spring Kafka依赖、单机可选compose profile、环境开关；旧本地MySQL/Redis无Kafka仍能启动 | 默认关闭Kafka时V1路由和B授权仍正常 |
| C2 | Mapper + Worker：`SKIP LOCKED`小事务领取有限批次、写leaseOwner/Until、commit后才send；ack成功/失败按租约owner状态转换，超时重新领取 | 双Worker无长期持锁；send成功DB更新失败可重复投递 |
| C3 | `fg_consumed_event`唯一eventId + `fg_usage_daily`UPSERT同事务；DB提交后ack offset | 消费/ack中间崩溃重放不双计数 |
| C4 | 管理端`/admin/usage`、`/admin/async-events`、`/admin/reconciliation`，固定watermark/已关闭UTC日期对账、失败安全重放/审计；前端真实表格和中文指南 | Kafka停机时显示“统计延迟”，余额从DB权威读；不以lag=0代表未失败 |
| C5 | Java/Node/隔离Kafka+MySQL/Redis真实故障脚本，脱敏证据和阶段SHA | 停Kafka→授权仍成功/outbox堆积→恢复→对账；重复事件只计1；最终FAILED可诊断 |

**C阶段不做新Gateway Kafka客户端**，不等待Kafka ack才放行、不重新扣余额、不将`SKIP LOCKED`当权威额度查询、不无界提交失败事件。Outbox状态及lease owner更新必须有条件where，不被迟到worker覆盖。全量性能留D。


## C-08 2026-11-08 当前执行合同：C0～C6

**优先读取`docs/design/v11-c-async-execution.md`全文和`CODEX_V11_C_EXEC_PROMPT.md`**；本节覆盖上面C-00～C-07模糊处。当前**只有C设计和任务，C代码/测试未实现**。B两项复审小修的源码/前端/SQL/JUnit已提交上游B，但ChatGPT尚未执行真实JVM/MySQL，故C0是不可绕过的测试门槛。

| 块 | 具体开发任务 | 门槛 |
|---|---|---|
| **C0** | 在隔离MySQL/Redis运行B修复后Java17、旧/新Node和真实HTTP；历史A同月两订阅、Balance冻结不匹配均409；Quota OFF时已发凭证可紧急撤销，重新开启旧代次不复活；只读`sql/v11-b-upgrade-preflight.sql` | 未实测通过**不做C1** |
| **C1** | Control pom加Boot管理的`spring-boot-starter-kafka`；Kafka单KRaft容器可选`mq` profile；`FLOWGATE_ASYNC_ENABLED=false`默认；新增`sql/flowgate-v11-c.sql`两表及严格8字段事件DTO | 无Kafka时B/V1继续可用，DDL两次幂等 |
| **C2** | `OutboxClaimService`短事务20条`SKIP LOCKED`；LEASED/owner/30s租约，事务外KafkaTemplate异步send；ACK owner条件SENT，失败有限退避/8次FAILED | 双Worker/迟到ACK/过期重领不吞事件 |
| **C3** | `UsageAggregationService`：校验Outbox+Ledger、唯一`consumed_event(eventId,chargeId,hash)`与`usage_daily`UPSERT同事务；Listener在DB commit后手动ack；DLT ACK不可确认则不提交原offset | 重放100次仅计1；事务/offset故障不双计 |
| **C4** | `/admin/usage`、`/admin/async-events`（含summary和ADMIN安全重试）、`/admin/reconciliation`只允许封账UTC日期；READ_ONLY RepeatableRead核对B Ledger + Outbox事件时间/Consumed + Daily | PENDING/MATCH/MISMATCH准确；RBAC/admin审计 |
| **C5** | `/console/`三组真实页面：“用量统计”“异步事件”“对账中心”，中文指南和生成静态帮助同步；SENT只表示Kafka ACK | 无假统计、无裸JSON/内部ID必填、Node通过 |
| **C6** | `scripts/v11-c-smoke.py`隔离Kafka/MySQL8.4/Redis7/双Gateway；停Kafka继续B授权、重启追平、Ack崩溃/Offset崩溃/租约竞态、DLT/手动重放、旧Caller回归 | 新脱敏证据、SHA/退出码/失败历史齐全 |

### C0：B小修前置实际交接

固定当前父B HEAD、用户未提交内容和专用随机端口Lab。先执行：
- `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`；旧`v11-b-console-test.mjs`、V11-A与V1产品Node回归；
- `python3 scripts/v11-b-smoke.py`（需先按README构建历史A二进制，勿覆盖B/current）；还需在隔离MySQL注入A允许的同月分段旧订阅及冻结Balance字段错配，使预检风险计数非零、下一笔新Grant被拒且余额/Ledger/Outbox不变；
- activation OFF前先签发、OFF后ADMIN成功撤销、旧代次仍不可恢复；OPERATOR/VIEWER仍403；新页面只有撤销入口，禁止签发/轮换。

若现有B小修测试覆盖不足，**先补完整可运行真实用例**。`docs/progress/evidence/v11-c0-b-fix-smoke.json`仅实际运行后生成，不得复制旧B20组冒充新证据；C0通过后再开始Kafka业务。若必须修复编译/SQL，保留B独立分支历史并明确C继承中的补丁。

### C1～C3：文件与事务提交规范

1. `sql/flowgate-v11-c.sql`：`fg_consumed_event(event_id PK,charge_id UNIQUE,payload_sha256,consumed_at)`及`fg_usage_daily(day_utc,application_id,api_id,authorized_units,grant_count,updated_at,PK(day_utc,app,api))`；原B/Outbox表只读不DROP。
2. `flowgate-control/service/{OutboxClaimService,OutboxPublisher,UsageAggregationService}`、`listener/UsageEventListener`和对应Entity/Mapper，小步使用现有MyBatis/TransactionTemplate、Spring Kafka API。Claim事务立即提交，Kafka await只能在事务外。Consumer先比对本地Outbox和Ledger，再同事务去重/UPSERT；ack只能在服务事务方法返回后。
3. 事件严格8字段`version,eventId,chargeId,applicationId,apiId,periodUtc,units,eventTime`。日归属用B Outbox的`eventTime`，不是可能跨日的Ledger插入`created_at`。Kafka本机单Broker不是HA或跨DB exactly-once；未启用异步不影响Quota。
4. 异常重投必须用原eventId；`SKIP LOCKED`只用于队列领取；Worker持有lease的CAS条件包括owner+id+状态和租约有效性；Consumer毒消息DLT失败不能ack源offset。

推荐提交：`test(v11-c): verify b review fixes in isolated mysql` → `feat(v11-c): configure optional kafka usage schema` → `feat(v11-c): publish leased outbox at least once` → `feat(v11-c): dedupe consumed grants transactionally`。

### C4～C6：运营和真实证据

- AdminAuthInterceptor.v11Path新增C路径：ADMIN才能POST FAILED重试，OPERATOR/VIEWER只读，Caller不可登录；返回不含Outbox payload原文。日期<=UTC昨天，查询最多31天、按(dayUtc,applicationId,apiId)复合主键稳定游标limit1..100（不能仅afterDay）；PENDING不等于错账。
- UI沿用`static/console`原生ES Modules，真实应用/API下拉、空/错/待处理显示；同步`docs/guides/product-console-guide.md`和`/console/guide.html`。真人375px/1280px仍留负责人。
- 新`scripts/v11-c-console-test.mjs`与`scripts/v11-c-smoke.py`最少证明：Broker down仍Quota授权并Outbox积压；恢复→aggregate追平；重投/DB提交未ack不重复；两个Worker lease互斥；封账日先PENDING后MATCH，故意更改隔离读模型后MISMATCH；安全重放不改Ledger/Balance；无Kafka旧V1/旧Caller不回归。
- 真实命令、退出码、通过/失败/跳过、Git SHA、版本、隔离容器信息回填本文件新增C-09（实施证据节，避免和本节冲突），仓库只留脱敏`docs/progress/evidence/v11-c-*.json`；未执行标未执行。完成后等待ChatGPT复审和负责人浏览器验收，**不自动进入D或合并main**。

**当前真实状态**：B小修代码已写、真实复测待Codex；C只有设计文档、任务书和开发指令，Worker/Consumer/UsageDaily/对账尚未编码。旧PR均Draft。

## C-09 本地实际实施（2026-11-08）

### C0 已通过，允许C1

基线675de47，继承B复审修复。Java17（Maven JAVA_HOME实际17.0.18；系统PATH裸java为21，不用于Lab）定向test/package退出0；5套Node通过。首次B Node旧文案断言失败，按紧急撤销新版提示修正后通过。真实双Gateway/MySQL8.4/Redis7脚本退出0，新增只读预检先零风险、注入旧A非重叠同月两订阅后风险非零、下一新Grant409且不建余额/Ledger/Outbox；余额冻结版本错配409无新写；OFF撤销成功，恢复发布后旧Token401。完整旧B并发/故障/身份链路也通过。新证据`evidence/v11-c0-b-fix-smoke.json`，不覆盖任何旧B/A证据。未触碰用户环境。

### C1 已通过

Control唯一新增starter-kafka，由Boot4.0.9解析Spring Kafka4.0.8/Kafka客户端4.1.2；其他模块不加依赖。Async默认OFF、mq profile可选单KRaft，无Kafka仍真启动Control/两个Gateway。严格8字段事件测试2项通过；两张C表DDL重复执行通过；真实Spring B服务100并发5额度、100幂等/跨月不受影响。命令`mvn -B -ntp -pl flowgate-control -am test package`及定向V11UsageEventTest退出0；`python3 scripts/v11-c1-smoke.py`退出0，证据`evidence/v11-c1-smoke.json`。未启动用户Kafka/持久卷。

### C2 已实现并验证

短事务SKIP LOCKED批次<=20、30秒租约与每轮唯一owner、过期第8次FAILED；事务外Kafka Future ACK/10秒上限/实例100在途，SENT和失败回写均校验有效lease+owner。ACK之前不写SENT，租约丢失更新0不报成功。新增2项Future/真实TransactionTemplate测试通过。`python3 scripts/v11-c2-smoke.py`真MySQL双线程各领取3条、有效租约6条不重叠、过期重领换owner、旧owner更新0/新owner1、Ledger不改，退出0；证据`evidence/v11-c2-smoke.json`。真实Broker故障与进程崩溃仍在C6验证，不以mock ACK替代。
