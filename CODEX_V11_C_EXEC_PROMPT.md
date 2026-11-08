# 本地Codex执行提示｜FlowGate V11-C（Kafka Outbox、消费幂等、用量与对账）

> **2026-11-08 当前唯一C开发交接**。V11-C现在只是**详细设计**，没有Kafka Worker/消费者/管理API/产品页新代码，也没有C运行证据。**先C0补测B复审小修，真MySQL不通过不启动C1**。用户本轮要求“设计C，并同步完成小修”，B修复已由ChatGPT提交GitHub实际源码，但**没有本轮独立的Java17/MySQL运行**。
>
> **严格按现成合同编码，不重新设计分布式业务**：`docs/design/v11-c-async-execution.md`（13节）、`docs/progress/v11-c-async.md`第C-08节（C0～C6）、`docs/reviews/v11-b-static-review-2026-11-08.md`与原B代码。所有旧Draft PR保留，main不改，Kafka只在C新增到Control。

## 0. Git/环境安全与基线

1. `pwd; git status -sb; git branch -vv; git fetch origin; git log -1 --oneline`。目标分支`feat/v11-c-async-usage`，父分支`feat/v11-b-quota`包含B核心与当前两项修复源码；未检出时`git switch --track origin/feat/v11-c-async-usage`。已有同名本地分支/未跟踪文件，先核对差异保护数据；不可reset-hard、clean、强推、改PR base或合并main。
2. JDK17/Maven，MySQL8.4/Redis7与已有Control/Gateway/IDE按需核查。**不碰用户现有MySQL/Redis持久卷、.env、IDE/JVM运行实例**，仅使用自建隔离Lab和随机端口。Kafka本机Broker通过单独`compose.local.yml --profile mq`或隔离脚本启动；默认OFF不启动。
3. 顺序阅读 `AGENTS.md`、`docs/design/v11-c-async-execution.md`、`docs/progress/v11-c-async.md` C-08之后、`sql/flowgate-v11.sql`、`QuotaChargeService`、`OutboxEventEntry`及Mapper、B现有Opaque Token实现（**没有JWT**），仍是`fgapp_`不透明Token；切勿改为JWT；`AdminAuthInterceptor.v11Path`与`/console/`原生ES modules。
4. 保持最少学习成本：Boot4当前BOM下一个Kafka starter；MySQL Mapper/TransactionTemplate；Spring KafkaTemplate+@KafkaListener；不引Kafka Streams、Redis Stream、Debezium/CDC、Nacos、Seata、分布式锁、全新消费者微服务、Vue/React/npm。

## C0：先用独立隔离环境验证B小修（阻断）

- B已提交`QuotaChargeService.requireFrozenBalance`、当月已有多历史订阅`occupied!=1`失败关闭、`AppCredentialController.revoke`在activation OFF无门禁、产品页只保留紧急撤销；新增`sql/v11-b-upgrade-preflight.sql`、JUnit和Node/B6脚本用例。这些源码**还没有由ChatGPT运行**。
- 本地必须真实跑：`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`，`node scripts/v11-b-console-test.mjs`及旧V1/A产品测试，`python3 scripts/v11-b-smoke.py`（按README准备历史A Gateway二进制）。只在隔离库执行预检并注入A旧同月双订阅/余额版本错配，验证内网409、Ledger/Outbox不变；OFF可撤销已有Token、恢复后旧Token拒绝、角色权限没变。
- 新`docs/progress/evidence/v11-c0-b-fix-smoke.json`只能真跑后写；旧B历史证据不覆盖。若遇源码编译或SQL边界问题，在当前C分支**先最小修B继承代码并说明**，直到C0通过才开始C1。**禁止“先实现Kafka，B修复留后面”。**

## C1：Kafka可选环境 + 两张新表

- 新增`sql/flowgate-v11-c.sql`仅`CREATE TABLE IF NOT EXISTS fg_consumed_event,fg_usage_daily`，保持B所有Ledger/Outbox/余额不变，DDL两次幂等。
- `flowgate-control/pom.xml`添加`org.springframework.boot:spring-boot-starter-kafka`（不写强制Kafka版本）；`compose.local.yml`只新增mq profile单KRaft Kafka；`flowgate-control/.../application.yml`新增`FLOWGATE_ASYNC_ENABLED=false`、topic/Bootstrap/group和有界参数。所有Worker/Listener/Kafka AdminTopic条件启用，默认Kafka不在也能启动Control并继续B计量。
- 定义严格8字段`UsageGrantedEvent`，对照旧B Outbox payload JSON（version,eventId,chargeId,applicationId,apiId,periodUtc,units,eventTime），不可偷偷改旧事件、不得带Token/Header/Body。
- Maven定向、OFF无Kafka真实HTTP与DDL二次执行后独立提交`feat(v11-c): add optional kafka usage schema`。

## C2：Outbox租约与至少一次发送

- 新`OutboxClaimService`短DB事务，最多20行`FOR UPDATE SKIP LOCKED`取得到期NEW/LEASED；每条写leaseOwner/leaseUntil(30s)/attemptCount后立即COMMIT，不在事务里等待Kafka ACK。
- 新`OutboxPublisher`在**事务外**KafkaTemplate.send，最多100在途、ACK上限10秒，成功按`id+owner+status+有效lease`条件SENT；失败退避NEW或第8次FAILED；迟到owner UPDATE影响0行不能覆盖新lease。
- Outbox消息必须重复使用已有eventId，不重扣额度。用2 Worker同抢、Broker ACK后杀Control、15/30秒租约恢复真实测试，不能因为Broker ACK前return就标SENT。提交`feat(v11-c): lease and reliably publish outbox`。

## C3：消费端DB幂等与ACK

- `UsageAggregationService`同MySQL事务先按eventId查Outbox原8字段、chargeId查Ledger验证字段一致，再INSERT`fg_consumed_event(eventId PK,chargeId UNIQUE,sha256)`，首次插入成功才UPSERT`fg_usage_daily(dayUTC,appId,apiId,authorizedUnits,grantCount)`。
- 日归属**只由B Outbox的eventTime UTC日**确定，Ledger.createdAt可能在午夜跨日，不可直接按DATE(ledger.created_at)聚合。
- KafkaListener使用`enable-auto-commit=false`和手动ack，**先事务业务方法成功返回（实际DB COMMIT）后acknowledge**；重复eventId/chargeId只略过累加，指纹不一致则EVENT_CONFLICT并诊断。Consumer异常整事务rollback，offset不确认；DLT必须有Broker确认才安全跳过原偏移，不保证时就停止分区/保留offset，不伪装成功。
- 用Kafka重复/DB聚合commit后故意不ack/毒消息/DLT断联真实测试，提交`feat(v11-c): dedupe consumption and aggregate usage`。

## C4：管理数据与对账

- 新`GET /admin/usage`、`GET /admin/async-events`/`summary`、`GET /admin/reconciliation`，唯一写API`POST /admin/async-events/{id}/retry`仅ADMIN、保留eventId且有审计。扩展AdminAuthInterceptor.v11Path使未知PUT/DELETE写路径先被403，不依赖前端。
- UTC日期最多31天，只对昨天及更早**封账日**对账，在同一REPEATABLE_READ只读事务按B Ledger+Outbox.eventTime、ConsumedEvent和UsageDaily比较；尚有未消费记录→PENDING，全部消费且一致→MATCH，不一致→MISMATCH。Kafka lag=0不能证明对账完成。MISMATCH首版只报告不自动改权威余额/流水。
- 数据分页/脱敏/低基数指标、失败状态准确；OPERATOR/VIEWER只读，旧V1 OPERATOR发布权限保持。提交`feat(v11-c): expose backlog usage and reconciliation`。

## C5：产品级后台

- 在`/console/`原生ES modules新增3个清晰导航：“用量统计”“异步事件”“对账中心”；已签发凭证/配额旧页面保留。真实API、客户/应用/接口名称选择、UTC日期、游标分页、未知/失败/加载/空状态，不把Outbox SENT写成统计完成、不把pending写成超额扣量。
- ADMIN失败事件二次确认重投，OPERATOR/VIEWER只读。更新`docs/guides/product-console-guide.md`、离线`static/console/guide.html`（依实际构建脚本生成）和README；Node ES模块/模拟DOM通过，真实1280/375px与键盘体验仍由负责人验收。提交`feat(console): expose async usage and reconciliation`。

## C6：隔离MySQL/Redis/Kafka真故障与阶段停止

1. `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`；旧A/B/V1 Node及新`scripts/v11-c-console-test.mjs`、指南`--check`；
2. 新`scripts/v11-c-smoke.py`隔离两Gateway+Control+MySQL8.4+Redis7+Kafka：100并发Free5只5Ledger/5Outbox，停Kafka时仍能Quota授权，恢复Broker后5ConsumedEvent、UsageDaily 5Grant、重启/重复/失败重排不会变成6；双worker租约、DB提交未ack、offset重放、毒事件DLT不可用、UTC边界、对账PENDING→MATCH→人为读模型差异MISMATCH；APP旧身份隔离/发布与V1旧Caller不回归。
3. 仅生成真实脱敏`docs/progress/evidence/v11-c-smoke.json`并将完整命令/退出码/实际JUnit/HTTP组数/失败修复历史/提交SHA回填`docs/progress/v11-c-async.md`后续实施节；用户数据库/IDE/旧Docker卷/.env不能操作。
4. 全部C代码与定向测试做完**停止等待ChatGPT复审**；不自行做V11-D正式四组长压测、不启动合并main或旧PR。Kafka本机单Broker只做可靠性演示，绝不宣称生产级多Broker高可用或跨库exactly-once。

**交付回报**：当前Git SHA/PR链接，C0～C6完成表，真实文件/SQL/API/GUI、Java/Node/HTTP/故障退出码，未完成和失败原因，所有新脱敏证据路径及哪些人工浏览器检查还需负责人执行。
