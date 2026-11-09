# FlowGate V1.1-C：Kafka、Outbox、异步用量、对账补偿（Codex 编码合同）

> 当前状态（2026-11-08）：C0～C6本地实现与定向验证已完成，等待ChatGPT复审与负责人浏览器验收；远端交付状态另记。C-00～C-08为历史设计合同，实际证据见C-09。本块是 **MySQL 账本 → Outbox → Kafka → 用量读模型**，绝不可将 Kafka 改成配额扣减的唯一事实来源。每条命令和实际测试证据按本块进度末尾回填。

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

### C3 已通过事务/幂等定向验证

严格比对本地Outbox八字段及Ledger后，同事务插ConsumedEvent与Daily UPSERT；唯一冲突先回滚后新事务确认，不把同charge不同event当正常重复。Listener在TransactionTemplate真实提交返回后ACK。首版按设计保守策略暂停毒消息分区、seek所有未处理offset、不ACK、不依赖未经确认DLT；修复后需重启消费者，C6实测偏移保护。

Java构建与2项Listener ACK顺序/失败不ACK测试退出0；`python3 scripts/v11-c3-smoke.py`退出0，真实MySQL100线程仅1次汇总、日表SQL故障没有孤立消费标记、修复可重放、日归属取Outbox UTC边界而非Ledger插入日、同charge换event冲突。证据`evidence/v11-c3-smoke.json`。初次冲突夹具用字符串替换忽略MySQL JSON空格，未真正改units；改结构化事件后验证通过。多阶段脚本曾过早设置passed=true，已改为最后一个probe成功才标通过，旧失败尝试不能作为证据。

### C4 已实现并通过真库查询

新增usage/async-events/summary/retry/reconciliation API及新V11 ACL路径；事件列表无payload，FAILED条件重排+审计同事务，原eventId/权威账本不动。Usage和对账完整(day,app,api)降序游标，最多31天/100条；对账只读REPEATABLE_READ单快照且仅封账UTC日。Broker状态来自有检查时间的有界真实Admin探测，OFF不建Admin客户端；进程计数只有固定枚举，重启归零。

`mvn -B -ntp -pl flowgate-control -am test package`退出0，新增游标2项；`python3 scripts/v11-c4-smoke.py`退出0，真MySQL同日两个App limit1分页不遗漏、PENDING→MATCH→手工读模型差异MISMATCH、重试仅一次审计/第二次409、今日400、事件数据脱敏。证据`evidence/v11-c4-smoke.json`。Kafka真实探测/运营HTTP权限留C6完整联调。

### C5 业务页面已实现

新增原生usage.js及“用量统计/异步事件/对账中心”导航，应用/API名称筛选、UTC日期、服务器复合游标、实际Broker/积压状态、SENT与已消费分离、ADMIN二次确认FAILED重排。读失败显示未知，空读模型不声称0消耗。B配额页同步异步语义，中文指南及同源离线HTML已生成。`node scripts/v11-c-console-test.mjs`、原product/B控制台测试退出0；新测试执行真实模块与模拟DOM，覆盖日期/游标/延迟/PENDING/只读/确认重投/断网。真实1280/375px及键盘体验未执行，留负责人。

### C6 最终真实验收（2026-11-08）

| 阶段 | 本地提交 | 实际门槛 |
| --- | --- | --- |
| C0 | cf8f22e | B修复先独立Java17/Node/旧数据预检/真HTTP通过 |
| C1 | 29cf09a | 唯一Kafka starter/严格事件/两表重复DDL、默认OFF无Broker B事务通过 |
| C2 | 8b64710 | 短事务、ACK与租约CAS；真MySQL双Worker互斥及旧owner0 |
| C3 | 1ee1730 | 100重复单聚合、SQL回滚无孤立标记、UTC事件日、提交后ACK |
| C4 | 1a54f16 | 真库复合游标、三态对账、审计重排、安全只读API |
| C5 | da1929f | 三组原生业务页面、真实API、角色/未知/延迟/指南 |
| C6 | 本节所在提交 | 真实Kafka崩溃、偏移、Broker/SQL故障、首次Topic恢复及安全兼容 |

**真实命令与结果**：

- `source .env`后`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`退出0。Maven与Lab使用JAVA_HOME指向Microsoft JDK17.0.18，未使用系统PATH上的Java21。Contract10/Control27/Gateway26/Demo4=**67项，失败0/错误0/跳过0**，仅直接受影响模块。最终配置修正后同一命令复查。
- `node scripts/v11-c-console-test.mjs`、`node scripts/v11-b-console-test.mjs`、`node scripts/v11-a-console-test.mjs`、`node scripts/product-console-test.mjs`、`node scripts/block2-console-test.js`、`node scripts/block3-console-test.js`退出0；新C测试使用真实ES模块/模拟DOM，包含查询、复合游标、统计延迟、PENDING、ADMIN二次确认重排和只读/断网。`python3 scripts/build-console-guide.py --product --check`退出0。
- `python3 scripts/v11-c-smoke.py`最终退出0，**26组真实故障/HTTP断言**。Kafka镜像4.1.1、Boot BOM客户端4.1.2/Spring Kafka4.0.8、MySQL8.4/Redis7、两个实际Gateway、Control及实际崩溃子JVM；全部随机回环端口/独占容器，证据`evidence/v11-c-smoke.json`。消费者明确使用`flowgate-usage-aggregate-v1`；毒消息分区实际提交offset在Broker断联前后保持103，不以不存在的-1 offset冒充保护。
- `python3 scripts/v11-c-off-smoke.py`退出0，证据`evidence/v11-c-off-smoke.json`：最终C版本无Kafka且OFF，旧OPERATOR可建服务/发布、VIEWER不可写，旧Caller授权/撤销，新增C只读API与资源均正常。C0～C4每步真库证据另存，旧B/A历史证据均未覆盖。
- `docker compose -f compose.local.yml --profile mq config --services`通过。Compose Kafka已同步C6验证的内外双监听方式；未用用户Compose启动/修改持久环境。

**26组主要运行事实**：

1. 无Kafka/OFF双Gateway100并发Free5精确5授权/95耗尽，5Ledger/5Outbox；App与旧Caller身份域仍隔离。
2. Async显式ON而Broker不存在时Control可启动、B授权200，Outbox积压6，运营探测BROKER_UNAVAILABLE。关闭UTC日HTTP对账PENDING，同一天多App limit1复合游标不丢页。
3. 实际Broker ACK后验收子JVM `halt(23)`，尚未回写SENT，原LEASED保留；实际消费者DB提交后`halt(24)`且未提交Kafka offset。生产Control恢复后等待真实30秒租约重领/同组offset重放，只有6条消费标记和6次聚合，不变成7。
4. Kafka重复发送原eventId100次，Daily和Consumed仍6；实际停Broker时新B授权继续200，恢复后追平7，未依赖Kafka扣额。
5. 两Worker在真实MySQL领取100条Outbox，租约不重叠，旧owner迟到回写0；实际到期后生产Worker追平107条，无额外Ledger。
6. 两管理员同时重排FAILED仅一个200另一个409、一次审计，eventId不变；读模型不双计。新C API ADMIN写、OPERATOR/VIEWER可读但POST/未知PUT/DELETE均403。
7. 对账PENDING→MATCH；隔离库人为增加Daily后真实HTTP显示MISMATCH，不自动改Ledger/余额。消费者日表SQL实际失效后第108个消费标记未提交，修复重启后原offset重放恰好108。
8. 协议毒消息暂停分区2、冲突payload暂停分区1；不累加、不ACK源offset、没有虚假DLT。Broker实际断联再恢复后原offset仍103。首版采用设计允许的保守分区暂停，严重不可恢复毒消息需负责人另行诊断处置，未开放跳offset/未确认DLT功能。
9. 首次配置新Topic且Broker离线时仍允许第109笔B授权；Broker恢复后定时有界重试创建显式声明Topic并投递。最终**109 Ledger=109 Outbox=109 ConsumedEvent=109 Daily准入次数**。Kafka未进入Gateway或B数据库授权事务。
10. 实际HTTP拒绝当天未封账对账/超31天，产品新资源与指南可达，运营API不含payload/凭证；消息日归属另由C3真MySQL边界probe证明取Outbox而非Ledger插入日。

**发现与修正**：C0旧文案断言失败已按新紧急撤销界面修正；C3冲突夹具初次未真正改MySQL规范JSON，改结构化事件后通过，并修正多阶段probe只能全部成功才设置passed。C6审查发现KafkaListener默认id会覆盖消费组，已显式groupId/idIsGroup=false，中断该次脚本并重新运行，最终证明同组offset而非跨组重放。补首次Broker离线的Topic重试恢复，实际新Topic用例通过。中断/失败的本机证据保留，不能当作通过；仓库只存最终脱敏通过摘要。

**依赖、中文注释与交付边界**：唯一新增Maven依赖为Control的Boot4 Kafka starter（BOM管理）；无Kafka Streams、CDC、DLT框架、Redis Stream、分布式锁、新微服务或JS框架。复用既有MyBatis-Plus/事务、官方KafkaTemplate/Listener、原生ES模块，无整块复制Plus业务。核心事件、领取、发布、聚合、对账和前端长流程有中文说明与`// 1.`/`// 2.`/`// 3.`；`git diff --check`通过。C两张新表仅CREATE IF NOT EXISTS，权威B表未改，唯一必要模型补充nextAttemptAt读取。

用户`.env`/IDE/既有JVM、MySQL/Redis持久卷未改或停止。测试只创建/清理自己的容器和进程；Kafka镜像缓存保留，无用户数据迁移。默认Async OFF，不替负责人激活。用户需备份确认后迁移C两表、选择性启动mq并给Control配置开关，再按中文指南体验。真实1280px/375px/键盘验收、ChatGPT源码复审、生产多BrokerHA、长期稳定性/10万级SQL和四组公平压测均**未执行**；本轮只作功能可靠性断言，不给吞吐/P95或生产exactly-once承诺。C到此停止，不进入D、不合并main/旧PR。

### Git远端交付结果（2026-11-08）

C完整代码/证据提交`90d4d5ca51d9b5e022a4ccc8a4f50980f91f0243`，当前分支`feat/v11-c-async-usage`。每步提交前检查分支/工作区/变更范围，仅显式暂存本阶段文件，无无检查`git add .`。工作未进入D，未合并任何PR。

实际`git push origin HEAD:refs/heads/feat/v11-c-async-usage`退出128，GitHub返回`Permission to Felix0410/FlowGate.git denied to Felix0410`、HTTP403。随后只读`git ls-remote --heads origin feat/v11-c-async-usage main`：远端C仍`675de477112d680a3695ba2cce114d63054b85a9`（设计），main仍`84a25d5a7b9545dff3556299efec3c310fc321cf`。**本地C已完成实现和定向验收，远端代码交付尚未完成**；没有改推main、强推、改base或重写历史。已知分支地址<https://github.com/Felix0410/FlowGate/tree/feat/v11-c-async-usage>当前仍是旧设计。gh未安装，公开GitHub PR API又返回rate limit403，未独立确认具体C PR URL，不编造PR更新状态。

本地审查补丁将导出`.local/acceptance/flowgate-v11-c-review.patch`（仅当前C相对远端设计的提交，无用户.env），可用于先审查。修复GitHub写权限后仍只能推当前功能分支。最终Docker检查只剩原`flowgate-local-mysql-1`和`flowgate-local-redis-1`，所有本轮Kafka/Lab容器已清理。当前等待ChatGPT和项目负责人复审。


## C-10 2026-11-08 ChatGPT远端源码复审与定向收口（三项已修，待复审）

> **实际审核起始C实现HEAD**：`08c48d015d320d7714fb8c9e70d09f8a5814de04`。截至此时GitHub已经真实包含C0～C6的Kafka、Outbox Worker、Consumer、MySQL日汇总、运营API、正式前端和Codex的Java67项/C6 26组真实故障验证。上述源代码和脱敏证据可远端读取；本节保留C-09中Codex`git push` HTTP403作为**当时**历史，不再声称“当前远端只有设计”。**ChatGPT未重新运行本机Kafka/MySQL/Java或真人浏览器**。

**最新审核报告**：`docs/reviews/v11-c-static-review-2026-11-08.md`。
**当前本地Codex唯一代码交接**：`CODEX_V11_C_REVIEW_FIX_PROMPT.md`。
**本轮停止条件**：不重做C0～C6、不提前开发V11-D、不合并PR #8/#9/main；三项P1定向实测后再复审。

| 优先级 | 新发现的未覆盖边界 | 通过条件 | 当前 |
|---|---|---|---|
| P1-C01 | Compose Kafka`KAFKA_LOG_DIRS=/tmp/kraft-combined-logs`无数据卷，容器重建日志丢失；Outbox`SENT`但未Consumed无法经现有只允许FAILED的重投恢复 | 独立Kafka命名卷保留KRaft数据；ADMIN审计+CAS可补投超过grace且确实未消费的SENT原eventId；隔离容器重建/人为丢消息后Daily追平、不多扣授权 | **已修，独立实测通过** |
| P1-C02 | `UsageKafkaConfiguration`对MySQL短暂异常与永久毒消息一律暂停分区，DB恢复却需要人工重启Consumer | 暂时DB/网络故障自动有界退避/恢复同offset、不先ACK、不双计；永久非法事件仍暂停、保留offset，其他分区继续 | **已修，独立实测通过** |
| P1-C03 | `AsyncUsageReadMapper.Sql.reconciliation()`以Ledger INNER JOIN Outbox作权威expected源，若Ledger缺Outbox直接漏报/可能空列表；多Outbox同charge可放大expected | 有界只读Ledger/Outbox完整性诊断、状态非MATCH/不伪造日期；真库孤儿Ledger和重复Outbox反例与前端中文提示 | **已修，独立实测通过** |
| P2 | 单Broker不具生产HA；不可恢复毒消息采用保守暂停，10万级SQL与公平压测、真人375/1280浏览器体验仍待 | 明确运维边界，性能归D，真实产品手测归负责人 | 历史限制 |

**当前阶段真实状态**：三项新增边界已完成源码、前端、指南及独立实测；最终Java70项、新增13组真实故障、独立持卷重建和原C6 26组回归均通过，详见下方实施节。新证据独立保存，旧C/B/A历史未覆盖；仍待ChatGPT复审与负责人真人浏览器，不进入D。

### C-10.1 F1 本地修复与真实验证

Compose新增独立kafka-data卷，实际日志目录改为镜像预置UID1000可写的`/var/lib/kafka/data`，无root初始化服务/无改用户卷。`scripts/v11-c-volume-smoke.py`从仓库Compose仅提取Kafka到随机项目，实际down（无-v）删除容器再up：消息offset0及已提交组offset1保留，最终只删本次新卷；20261108T124634本机证据通过。

新增ADMIN `POST /admin/async-events/{id}/replay`，SENT且未消费、超过默认300秒等待期才能CAS排NEW，保留payload/eventId，审计同事务，不访问余额/Ledger。新Broker ACK回写时将SENT的next_attempt_at更新为ACK等待期基点；应用启动也需经过完整grace，保护旧SENT时间含义不同的存量行。升级须全部Control Worker使用新版本后再补投，不混用旧版ACK写法。可配置1..86400秒，仅独占Lab设置2秒，用户默认300未改。FAILED retry含义保留；安全摘要/页面新增超期SENT未消费数、等待期与独立确认按钮。

`mvn -B -ntp -pl flowgate-control -am test package`退出0；新旧Node通过。`scripts/v11-c-review-smoke.py`F1阶段真实新Broker完全丢原记录（topic size=0），MySQL仍1Ledger/1SENT/0Consumed；最近SENT409、双管理员仅200/409、一次审计、原payload/eventId不变，补投后1Consumed/1Daily且余额不变、已消费不可补投、OPERATOR/VIEWER403。20261108T124854本机证据通过；最终三项汇总证据待F4，不覆盖任何旧C证据。用户持久Kafka未启动/迁移。

### C-10.2 F2 本地修复与真实验证

新增UsageConsumerErrorHandler，框架包装cause链分类DataAccess/SQL连接/事务超时，委托官方DefaultErrorHandler自动同offset重试。指数1/2/4/8/16/30秒封顶，长期故障低频周期再试，绝不ACK恢复成成功；协议/权威内容冲突仍seek同poll未处理记录并仅暂停问题分区。固定诊断区分transientRetry与poisonPause（保留旧pausedPartition兼容，均为进程计数）。不引新依赖。

新增两项Java分类/分区边界用例，定向Maven test package退出0。20261108T125916真实Lab：消费已进入fg_consumed_event SQL锁等待后实际docker pause MySQL 5秒再unpause，释放测试锁，不重启Control/Listener，PID不变，原offset自动追平2Ledger/2Consumed/2Daily，transientRetry>=1、poisonPause=0。再发永久非法和内容冲突分别暂停，分区2提交位点不前进，其他分区合法重复仍被消费，不双计。无原始payload/异常消息记录，无假DLT。F4再合并F3及原C6回归。

### C-10.3 F3 本地修复与真实验证

新增LedgerIntegrityMapper，在同一只读REPEATABLE_READ快照内，按日期/应用/API过滤、每类及总样本上限101（返回100）诊断Ledger缺Outbox、Outbox缺Ledger、同charge多Outbox及事件时间缺失。事件日期未知时只用写入时间及相邻UTC日窗口作候选筛选，不将其归入日报；响应明确integrityScan范围及INTEGRITY_GAP、是否截断。正常日归属仍Outbox.eventTime；expected同charge只取最早事件防放大，多事件另行报警。本次范围有异常时不能宣称MATCH，包括日报行为空的情况。无权威数据自动写修复。

新增Java只读RR/空日报不掩盖异常用例，受影响Java test/package通过；前端空日报仍显示“Ledger存在但事件日期未知”，新旧Node通过。首次DOM验证发现直接replaceChildren传null会在浏览器显示字样/模拟DOM报错，已改为空参数展开并通过。

20261108T131109完整真实复审Lab已通过12组：基线旧INNER JOIN在缺Outbox时实际为0；新API仍返回INTEGRITY_GAP且不伪造日期；恢复原事件后真实Kafka消费PENDING→MATCH；重复Outbox时expectedCount仍1/关联数2并报警；反向孤儿也被发现，移除坏测试行后MATCH，余额/Ledger未改。新增三项集成曾被并行重打包共享target Jar污染，出现延迟类加载NoClassDefFoundError，失败不算通过；现Lab使用独立不可变Jar副本，重跑全部新例通过。F4整体验收与原C6回归单独记录，不覆盖历史证据。

### C-10.4 F4 最终定向验收与证据

代码分步提交：F1 `7849497`、F2 `71b4f6a`、F3 `49bf548`。本节测试提交还包含运行Jar隔离和诊断候选边界收尾：Outbox后来恢复/写入较晚但事件时间缺失时，按关联Ledger的候选写入窗口仍能诊断，绝不将其写入时间直接当日报日期。SENT超期告警仅扫描最近1000条事件候选，API/页面明确显示范围，不冒充全历史数量；真MySQL运营probe补查通过。

实际命令（均先source .env，Maven/Java子进程JAVA_HOME为17.0.18）：

- `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`最终退出0：Contract10+Control30+Gateway26+Demo4=**70项，失败0/错误0/跳过0**。较原67新增数据库异常分类/分区seek边界2项、空日报完整性与只读RR1项。无全项目统一回归/长期压测。
- `node scripts/v11-c-review-console-test.mjs`、`node scripts/v11-c-console-test.mjs`、`node scripts/v11-b-console-test.mjs`、`node scripts/v11-a-console-test.mjs`、`node scripts/product-console-test.mjs`、`node scripts/block2-console-test.js`、`node scripts/block3-console-test.js`全部退出0。新测试实际执行模块/模拟DOM，覆盖SENT独立补投确认与ADMIN、等待期禁用提示、超期告警、空日报仍显示INTEGRITY_GAP。非真人浏览器验收。
- `python3 scripts/build-console-guide.py --product --check`退出0，指南/离线HTML同步。资源更新后曾执行package -DskipTests仅打包，不计作测试通过；最终上述70项完整test/package已实际重跑。
- `python3 scripts/v11-c-volume-smoke.py`退出0：独立随机Compose仅Kafka/独立新卷，真实down（无-v）后up容器ID改变，offset0消息和已提交组位点1保留，UID1000目录可写，最终只删本次卷。
- `python3 scripts/v11-c-review-smoke.py`最终退出0，**13组新故障/HTTP场景**。实际Broker ACK且SENT未消费后丢弃独占Broker重建空记录、等待期拒绝、双管理员补投200/409+一次审计、原payload/eventId保留、无新Ledger/额度消耗、已消费拒绝、OPERATOR/VIEWER403；实际MySQL消费SQL途中pause/unpause且无Control/Listener重启自动追平；永久毒消息/内容冲突暂停而其他分区继续；孤儿Ledger/重复Outbox/反向孤儿/事件日期缺失显式GAP，恢复正确测试数据后MATCH，不动权威余额/旧Ledger。最终新Lab为3条真实Grant/3消费/3Daily次数。测试等待期仅2秒，生产默认300未改。
- 原`python3 scripts/v11-c-smoke.py`重新实际执行退出0，**26组原C6回归**，最后109Ledger=109Outbox=109Consumed=109Daily次数。原C6/B/A全部历史JSON未改；本次原回归raw另存`.local/acceptance/v11-c-20261108T131016.json`。最终修复数据raw `.local/acceptance/v11-c-review-20261108T133020.json`，12组中间通过/失败尝试保留但不冒充最终。
- 受限告警SQL收尾又运行`python3 scripts/v11-c4-smoke.py`退出0，真MySQL运营查询/三元组游标/三态对账/审计脱敏通过；不是以源码字符串自我检查替代SQL运行。

唯一新增汇总证据`docs/progress/evidence/v11-c-review-fix-smoke.json`，包含新13组、卷重建和原26组回归、Java/Node结果。`git diff --check`及中文关键分点检查通过，未新增依赖/框架/微服务/DDL，不改B扣额数据面。原MySQL/Redis Compose服务定义逐字对比保持原样。没有读模型自动改写余额/历史Ledger，没有未经ACK的DLT或跳offset。

失败历史：新增整体第一次后半段曾受共享target Jar并行重打包污染，出现延迟加载NoClassDefFoundError，最终passed=false未算通过。Lab现在把运行Jar复制到独占临时目录（Control/双Gateway/Demo及probe均一致），新13组重新通过；原C6也完成回归。前端直接replaceChildren(null)的空分支已改为不传该参数，Node重跑通过。没有隐藏这些失败。

当前限制：完整性诊断是明确日期/应用/API候选窗口和100样本，不是全历史审计；JSON关联/索引/10万级EXPLAIN与正式压测仍待D；Kafka单Broker不属HA；永久毒消息仍需负责人诊断处置。真人1280px/375px/键盘/角色体验未执行。用户.env/既有JVM/数据库持久卷未改；常驻Kafka新卷启动单独征询负责人，不能把配置和独占卷测试当作用户环境已部署。所有技术修复至此完成，等待复审，不进入D/不合并PR。


### C-10.5 本地技术交付汇总

本轮功能分支`feat/v11-c-async-usage`，三项主修复提交`7849497`、`71b4f6a`、`49bf548`，测试/收尾代码提交`1b53fb6`。最终独立证据为`evidence/v11-c-review-fix-smoke.json`，没有覆盖原C的67/26历史或B/A证据。默认Async仍OFF，补投默认等待300秒；仅测试进程显式缩短到2秒。

README/AGENTS/中文指南及离线HTML同步，未新增Java/npm依赖、Kafka Streams/CDC/微服务，不引入任何数据库DDL/自动修复权威余额。SENT补投和完整性异常均有产品按钮/中文解释及真实权限；常驻启动涉及新Kafka命名卷，已给负责人单独确认选项，当前未收到该项授权，未擅自启动用户Kafka或迁移任何原数据。源码测试通过与实际常驻部署分开记录。

已到本轮修复停止门槛，后续只等待复审、负责人真实1280px/375px/键盘体验及明确授权部署；不自行进入D、不合并main/PR #8/#9。远端交付状态以随后实际push/ls-remote结果记录，不用历史403推断当前远端没有C代码。


### C-10.6 远端交付核验

原生git push本次仍返回HTTP403，未改凭证、未改推main。随后通过已连接且确认具写权限的GitHub应用，将完全相同的27个修复文件交付到原功能分支feat/v11-c-async-usage：代码提交0240936728cea7dc5eae1736d4acac062edbce57，父提交ed5a312da82de0d1bfbfae00878fb8d42a4ade82。远端源码树0063a6342d6b7a77963a0c6765434cc6b6a21acf与已测试本地de8aa28a82d61c32b9a12a75f5e8dc5a99b97480的树逐字一致，原生git ls-remote也已确认代码提交。

更新引用使用expected_sha核验原HEAD且force=false，只推进同一C分支；main及旧PR base未改，PR #9保持Draft。原本地分步提交全部保留于本地feat/v11-c-review-local-history-de8aa28归档分支（不推送）；当前交付说明另作后续文档提交，再将本地同名功能分支跟踪远端，不reset/rebase或重写共享历史。

上述成功是修复源码与证据的远端交付，不是用户环境常驻Kafka部署。常驻Kafka新卷启动授权仍未收到，用户.env/MySQL/Redis原持久资源未改，Async默认OFF。等待ChatGPT复审和负责人真人浏览器验收，不进入D、不合并。


## C-11 2026-11-09 ChatGPT复审闭环：三项P1源码与证据审核通过

**远端最新提交**：最初C复审基线`ed5a312...`，本次实际修复代码`0240936728cea7dc5eae1736d4acac062edbce57`，后续实际交付说明`23f33bd9f73303276cd08ecf737396b795adcc64`。ChatGPT本轮已经从GitHub读取修复后的源码、`scripts/v11-c-review-smoke.py`、`scripts/v11-c-volume-smoke.py`和新增`docs/progress/evidence/v11-c-review-fix-smoke.json`。**本轮没有重新在用户机器执行JDK17/MySQL/Redis/Kafka，也没有真人浏览器验收**。

**正式复审结果**：`docs/reviews/v11-c-review-fix-verification-2026-11-09.md`。
- P1-C01 **通过**：Kafka数据卷（仅新Kafka）真容器删除重建topic/offset保留；受控SENT未消费ADMIN补投沿用原eventId/payload，grace/CAS/审计/角色门禁，完整隔离故障证据。
- P1-C02 **通过**：数据库临时故障通过Spring Kafka DefaultErrorHandler有界间隔同offset自动重试；真MySQL pause/unpause后不重启Control自动追平，永久毒事件仍暂停不ACK。
- P1-C03 **通过**：Ledger缺Outbox、反向孤儿、重复Outbox及事件时间缺失被只读RR有界查询判`INTEGRITY_GAP`，不误显示0/MATCH或重复计算Grant。真MySQL损坏夹具/恢复与前端证据齐全。
- Codex新脱敏摘要：Java70项、7套Node、新13组故障、独立Kafka持卷重建、原C6 26组+109笔守恒回归通过。ChatGPT仅复核远端源码与该证据**一致**，不宣称自己的二次运行。
- **技术审核结论：C已具备转入D设计/验收规划的条件，没有遗留本次三项P1阻断项**。但C未得到用户常驻部署或数据迁移授权；Async仍默认OFF，Kafka单Broker非生产HA，10万级SQL/公平性能/真人产品浏览器/PR主线收口尚未完成。

**后续停止条件仍有效**：本次只完成C复审和文档/PR状态更新。未经负责人单独许可不自动开发D、不开启用户Kafka新卷、不合并#1～#9/main。
