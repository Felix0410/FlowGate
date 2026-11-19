# FlowGate V12-M｜RabbitMQ单中间件收敛与Kafka退役——Codex逐段实施合同

> **仅设计定稿，M0～M8全部NOT_STARTED**。方案见[完整架构](../design/v12-m-rabbitmq-migration-architecture.md)，真实旧源码审计见[现状核验](../reviews/v12-m-preflight-audit-2026-11-19.md)；独立设计分支`docs/v12-m-rabbitmq-convergence`，父`feat/v12-b-orders@c857d3b8532ae98dcfb208d10dd140efa0a3037b`。V12-B PR #17仍Draft，需先复审其真实源码/证据；旧B功能分支相对旧B设计分支behind5的ADR-14差异以本设计的最终决定覆盖，但**不能通过修改PR#17/head/base/rebase来实现**。本阶段不含V12-C秒杀、不自动合并任意PR。

## 总体编码纪律与停止条件

- 本地Codex先`git fetch origin`、`git status -sb`、`git log -1`并核对本设计分支最新HEAD，**从本分支**新建`feat/v12-m-rabbitmq-only`，独立Draft PR base=`docs/v12-m-rabbitmq-convergence`。每个M阶段1～数个Conventional Commits，记录真实SHA、受影响源码、修改理由、JUnit/Node/Python/HTTP退出码和失败记录；每完成后同步旧Console异步运维页面与中文指南，不积攒到最后。长Java方法按`// 1.`、`// 2.`、`// 3.`中文注释。
- 允许原方案已有技术：Java17、SpringBoot4、SpringAMQP/RabbitMQ、MySQL8.4、Redis7、Spring Cloud Gateway、MyBatis-Plus、原生ESM。**过渡兼容版本可短期同时编译Kafka+Rabbit以执行受控切换，最终包必须无Kafka客户端、Starter、Broker、Topic配置及运行脚本依赖**。不引入CDC、Seata、新服务或前端npm框架。
- 仅新建带owner标签、随机项目名和端口、全新MySQL/Redis/Rabbit/(仅迁移演练时新Kafka)容器及卷，日志/证据脱敏。**旧用户.env、.local实际账号/密钥、IDE/JVM、个人MySQL/Redis/Kafka/Rabbit容器与持久卷、历史Ledger、git未提交修改全部保护**；禁止`docker down -v`、DROP/TRUNCATE用户库、直接删旧卷、reset-hard、clean-fd、force-push、合并main或旧PR。迁移真实常驻环境必须另获明确授权并另定维护窗口。
- M目标是**旧用量业务的无重算迁移**，不是V11和V12两套账本合并，也不是从Kafka offset批量导入。V11原8字段`UsageGrantedEvent`、`UsageAggregationService`、旧`fg_quota_ledger`/`fg_outbox_event`/`fg_consumed_event`/`fg_usage_daily`不能改历史行/主键/时间语义。

## M0｜B前置验收与全仓只读基线（失败就停止）

**必须先做**：
1. 读取PR#17真实最新SHA与全部B代码：MySQL6表、Commerce Rabbit收据/退款、B0 A三项P1修复；**核查最终B的真实证据文件是否已经补齐**。截至本设计审计GitHub仅看到B0两个PASS JSON+三份失败记录，未见`docs/progress/v12-b-implementation.md`、`v12-b7-e2e.json`，B进度B1～B8还写NOT_STARTED。允许本地Codex在自身功能分支以有实测依据的证据补齐交付，不能捏造，若B明显不完整则先停止请负责人处理，不要为了开始M跳过B安全门槛。
2. 逐个定位全部Kafka引用，记录`flowgate-control/pom.xml`、`application.yml`、`async/*`、`listener/UsageEventListener`、`service/OutboxPublisher`、`AsyncOperationsService`、Compose、`.env.example`、Python启动器、V11 Kafka脚本/单测、README/Console；保存`rg -n 'Kafka|kafka|NewTopic|KafkaTemplate|spring.kafka|FLOWGATE_KAFKA' --glob '!docs/**'`原始匿名检查摘要。
3. 在**自有全新隔离实例**确认原V11 Ledger=Outbox和Consumed/Daily一致的封账日，记录各status/未消费/失败/租约/poison分区事实；A Free5与B Commerce已有必要定向回归通过，发现既有源数据不一致先出报告，禁止自动修复。
4. 建立可审计清单：当前所有Control实例(版本/owner/PID/异步开关)、旧Kafka Producer/Consumer是否真正运行、可能存在的旧Kafka分区暂停/lag、Rabbit Broker3.13.7真实quorum队列、版本/feature flags、当前Git/构建SHA。**不能假设只有1个Control**。
5. M0证据文件`docs/progress/evidence/v12-m0-baseline.json`含代码/环境归属、真实B测试状态、需处理文件清单、数据完整性检查结果及不可用/未执行项。不能在M0碰原用户实际服务/消息。

## M1｜旧V11用量的Rabbit兼容实现与单路由栅栏（先不删Kafka）

**文件目标**：
- 只在新隔离库显式执行`sql/flowgate-v12-m.sql`，新表`fg_legacy_mq_cutover(id=1,mode=KAFKA/FROZEN/RABBIT,epoch,cutover_max_outbox_id,changed_at/by)`，重复执行两次不修改已有记录；权限受ADMIN与可信内部实例身份控制，不能允许普通Portal通过QueryParam改Broker模式。
- `flowgate-control/src/main/java/com/flowgate/control/async/LegacyTransportFence.java`、`LegacyUsageRabbitConfiguration.java`、`LegacyUsageRabbitPublisher.java`、`LegacyUsageRabbitListener.java`、`LegacyUsageRabbitOperations.java`（命名可按项目包实情微调）。**只复用**旧`UsageAggregationService.process`/原`UsageGrantedEvent`/原旧MySQL Outbox Claim和B0已修RabbitDelivery可靠边界；不要复制账本成第二份日模型。
- **旧Kafka Publisher也必须在所有升级过的Control里尊重fence**，所有实例唯一单向模式，切换到FROZEN后停止新claim并等待在途回执/租约；新Rabbit只在RABBIT模式领取/ACK，模式状态及epoch来自数据库，不能只是JVM启动时缓存。
- Rabbit Legacy单独Exchange+quorum主/Retry/Poison、Confirm+mandatory return、持久消息，手动ACK在MySQL事务提交后；保持原eventId、chargeId、eventTime、完整8字段和规范指纹，不能误喂给V12新Rabbit/API用量队列。
- 消费的永久坏JSON/Jackson3、版本、无权威Ledger/Outbox、指纹冲突→Poison，临时DB故障持久retry确认后ACK原件，未知异常有限12次后持久隔离；所有错误码低基数、日志不记录原始payload/Token；真实Rabbit quorum`at-least-once`+overflow`reject-publish`+stream_queue参数验收。
- 本阶段Kafka可仍在**仅自有迁移演练环境**并作为备用；真实用户旧实例不因代码创建就被切换。所有Kafka client尚未删除，以便M2严谨验证。**此阶段只验证兼容双构建，但运行发布路由必须严格单一**。

**定向测试**：JUnit老8字段/解析+坏Jackson、Publisher确证/return、Listener commit/ack、伪造/错eventId进Poison、重复100次只在旧fg_consumed_event留一条；SQL数据库模式/epoch CAS；双Control只一个有效Rabbit业务路由。保存`v12-m1-legacy-rabbit.json`。

## M2｜切换编排、旧SENT未消费/FAILED权威恢复

**必须实现一个**仅ADMIN可触发的隔离切换脚本或可读诊断/操作API（如`scripts/v12-m-cutover.py` + `/admin/async-migration/**`），它在每个阶段**先只读检查、明确输出下一步、失败停止**，不允许请求参数`force=true`忽略安全门槛。所有破坏性操作限定自有隔离库；正式部署另需维护审批。

**固定操作序列**：
1. 检查所有Controller为兼容版本，旧无法识别fence的二进制均停止且不可能再自动重启；记录每实例、Kafka Producer in-flight、Kafka Consumer分区状态；如果无法确认完整名单/旧进程仍在，**停住，不切换**。
2. 在安全维护窗口停止旧Kafka发布新领取，等待在途ACK/leases直到有确定状态并把DB模式`KAFKA→FROZEN`以epoch CAS写审计；旧Kafka Consumer可短暂排空，随后全部停止/撤销；Gateway同步QuotaCharge仍可写新旧业务MySQL Ledger/Outbox，因此用量只暂延时，不伪造下游失败返额。
3. 事务快照查明`fg_outbox_event`最大id与未消费高水位、旧Kafka分区lag和poison暂停事实；**旧Broker无法恢复也要记录理由与从权威MySQL追偿范围**。不靠Kafka Topic消息数直接判断已消费。
4. DB严格过滤原`USAGE_GRANTED`：NEW自然等待Rabbit claim；过期LEASED待租约超时；FAILED需ADMIN核验原Ledger/Outbox再单独重排；SENT未消费必须已过原300秒冷却且实例启动亦满300秒，批次上限<=100，`NOT EXISTS consumed`和旧状态CAS限定当前holder/epoch，重排不生成新EventId/ChargeId；异常/孤儿列INTEGRITY_GAP并停止。
5. 只在Kafka旧进程及其Publisher/Consumer确已停止后，切`FROZEN→RABBIT`+epoch CAS。启动Rabbit Legacy Publisher/Consumer，恢复上述缺口并最终核对`fg_quota_ledger`→`fg_outbox_event`→`fg_consumed_event`→`fg_usage_daily`。任何阶段如果数据库完整性无法证明必须FAIL-CLOSED、给出操作审计和待恢复数，而不是改旧账本凑MATCH。
6. 单个Control实例重启、Rabbit Broker掉线和重新确认、旧SENT 300秒审计补投、已消费同EventID重复100次、交叉两个Control（禁止不同mode同时发布）、原用户看到真实PENDING/MATCH，必须用**真实数据库与真实Rabbit**验证，而非只Mock。

**必须有回滚**：在最终删Kafka代码之前，若Kafka模式切换前失败保留旧路径；若FROZEN阶段失败允许审计复位（先证明所有Rabbit publisher未运行）；已RABBIT又需退回旧模式时先停Rabbit并核对outbox/consumed后单独审计CAS，**绝不两个Broker同时开**。最终Kafka-free版本不支持静默回滚旧Kafka，必须退回上一个Rabbit-only可靠版本。证据`v12-m2-cutover.json`列版本/epoch、oldInFlight=0、旧消费者退出、扫描高水位和每组缺口、真实操作审计/退出码、回滚练习。

## M3｜旧V11运营、前端和帮助同功能更新

- 保持`GET /admin/usage`、`/admin/reconciliation`、`/admin/async-events`、`/admin/async-events/summary`和`retry/replay`原路径、角色/分页/只读RR快照/300s等待期；统一将Kafka partition状态替换为Rabbit Broker/legacy主队列/Retry/Poison/Consumer实际ready与检查时间、当前cutover mode/epoch、权威MySQL待消费数量，不能仅展示“Rabbit已连接”就说数据MATCH。
- 旧V11和新V12读模型分开，Console导航可按“用量分析/消息运维”合并入口但细节带`V11_APP`/`V12_APP_API`来源。主功能不可随机删；ADMIN危险重排有确认、审计和冷却；PORTAL用户无运营越权权限。
- 原生ESModules工作台、真实中文指南`docs/guides/v12-m-rabbitmq.md`、`docs/guides/product-console-guide.md`、`README.md`同步；无假“Kafka已经删除”文案。B订单Console/Portal和历史Free5不退化。M3真实浏览器1280/375/键盘与两类角色检查。
- 保存`v12-m3-console.json`，失败与未运行状态明确；M3后仍处于兼容过渡，不能宣称最终无Kafka。

## M4｜最终Kafka代码、依赖、Compose、脚本清退

只有**M2真实迁移一致性成功且新Rabbit可靠**才能开始最终删除。按[完整架构](../design/v12-m-rabbitmq-migration-architecture.md)的清单修改：
- Java：删除Kafka专有`UsageKafkaConfiguration`、`UsageTopicRecovery`、`UsageConsumerErrorHandler`、`UsageEventListener`、`AsyncConfiguration`中`NewTopic`，替换`OutboxPublisher`、`AsyncBrokerProbe`和`AsyncOperationsService`中的Kafka依赖；保留旧Quota/Usage Aggregation/Outbox Mapper的权威一致性。
- Maven：删除`spring-boot-starter-kafka`与传递Kafka client；从`application.yml`、`.env.example`删`spring.kafka`、`FLOWGATE_KAFKA_*`、旧`TOPIC/GROUP`，保留`flowgate.async.enabled`作为Rabbit旧用量的统一业务开关（需在产品指南解释其含义）。
- Compose：当前`compose.local.yml`、`compose.v11-demo.yml`从运行依赖移除Kafka service及新服务卷定义，Rabbit作为本项目现行唯一Broker；可整理原`compose.v12-a.yml`命名，但所有用它的脚本要同步更新，防止双声明Rabbit服务或重用旧用户卷；**不要清理任何实际存在的Kafka volume**。
- 运行器：`v11_local_runtime.py`默认port字典、项目Owner/容器名单、启动/doctor、Kafka CLI和旧Java探针替换为Rabbit queue/consumer确认；`v11_local_core.py`的`kafka_probe`替换；`v12-b-smoke.py`等隔离脚本只启动Rabbit，不再要求`FLOWGATE_KAFKA_PORT`。旧Kafka专有单测/脚本在现行测试路径中删除或迁至历史说明，不允许编译依赖Kafka；历史Git实测证据仍可查。
- 防残留扫描`rg -n 'org\\.apache\\.kafka|org\\.springframework\\.kafka|KafkaTemplate|KafkaAdmin|KafkaListener|spring\\.kafka|FLOWGATE_KAFKA_|apache/kafka' flowgate-* scripts compose* .env.example`需**现行代码/运行配置无命中**（脚本注释和历史说明另处理，不误删历史证据）；`mvn -pl flowgate-control -am dependency:tree`无Kafka客户端，`docker compose config`无Kafka service。
- 保存`v12-m4-removal.json`含所有变更文件/依赖树/当前Compose服务列表、任何未清除项及保留用户旧卷证明。**不合并main、不操作原用户Kafka**。

## M5｜真实Kafka-free主业务闭环与三类Rabbit链路

在新隔离环境中**完全没有Kafka容器、Kafka Jar和Kafka env**地：
1. V11管理真实发布/订阅/APP短期Key、旧APP月订阅，经双Gateway Free5=5×HTTP200 + 第6次429（如使用配置专用Free5），核对`fg_quota_ledger=fg_outbox_event=fg_consumed_event=fg_usage_daily`按事件单位和次数吻合；同eventId重复100次旧Daily只增一次，旧封账UTC日MATCH、今日只标PROVISIONAL/PENDING。
2. V12-A普通Portal用户真实商品/免费权益、自助APP/API月池、双Gateway5/6调用，`fg_api_usage_outbox→Rabbit usage.v12→fg_api_usage_daily`追平；严禁把V11事件投进V12新消费模型、两类配额互相扣错。
3. V12-B真实正价SKU→幂等创建PENDING→模拟付款收据→Rabbit Commerce→唯一PAID_ORDER Grant+APP/API月余额→实际Gateway授权；退款时未使用与已使用结果正确，重复100次商业事件不重复开通。
4. 旧Console用量诊断/重排、B Commerce运营诊断、普通Portal订单/额度页，真实1280/375浏览器与键盘交互、跨用户404、ADMIN/VIEWER读写分离。
5. 运行时任务边界中只有一个Rabbit Broker（里面三套独立队列），MySQL8.4/Redis7、Gateway/Control/Demo；**不要用“Kafka容器没启动但应用仍带spring-kafka依赖”冒充完成**。

**证据**：`v12-m5-kafka-free-e2e.json`包含实际Docker服务、构建依赖、事件笔数、旧新两类Daily与Ledger摘要、不同队列消费记录、HTTP状态/失败历史和源码SHA，浏览器真实结果另存；只有实际执行成功才`PASS`。

## M6｜异常切换/故障恢复/边界验收（定向，不做D级性能实验）

- Kafka源旧SENT尚未消费、旧Consumer毒分区暂停、旧Publisher确认后进程死亡、历史FAILED与租约超时、老旧实例强行连接/混合版本启动被拒绝、两个Control同时试图切换模式只一个CAS成功；历史`fg_consumed_event`未发生重复，权威Ledger不变。
- Rabbit Legacy的队列持卷重建、Publisher Confirm NACK/mandatory return、MySQL短断后TTL retry→主队列、坏JSON/无法解释Ledger→Poison、消费者DB COMMIT后ACK丢失产生redelivery，恢复仍日汇总准确；Rabbit A用量和B Commerce同时存在且互不抢队列。
- 脱敏的旧Kafka迁移模拟源可以在单独的**迁移演练轮次**使用自有新Kafka容器，最终验收轮次必须**彻底没有Kafka**，把两套运行结果区别记录，防止把“演练期间Kafka还在”误说成最终Kafka-free。
- 不强制10万级SQL性能、四组路由公平压测或V12-C 20k并发；旧V11历史B23/C26故障矩阵若没重新跑须保留NOT_EXECUTED并列出关键已执行子集。所有必须的迁移/重复/断连/恢复用真实容器（不能只Mockito）。
- 证据`v12-m6-failure-matrix.json`，失败尝试必须完整保存，不把调试失败算通过。

## M7｜说明书、维护窗口、回滚与项目简历技术主线

- 更新全部现行启动指南/产品Console指南/API用量说明、V1.2路线图、`AGENTS.md`、`.env.example`，把**最终技术栈**表述为Java17+SpringBoot4+Spring Cloud Gateway+MySQL+Redis+RabbitMQ，不再把Kafka列为项目目前依赖。允许旧V11历史文档出现Kafka，但必须标明当时曾用。
- 提供`docs/guides/v12-m-kafka-retirement-runbook.md`，说明迁移前旧Ledger/Outbox/Consumed/Daily备份、全实例列表、停写/高水位与工作窗口、RABBIT栅栏、受控补投、验收判定、旧Kafka数据卷只读归档/无需立即删除、恢复计划。
- 只更改**仓库代码和隔离演练**，不能据此声称已经迁移用户现有机器或已删除旧用户Kafka容器。默认保留旧卷/历史数据供恢复，不写`docker volume prune`等危险命令。
- 保存`v12-m7-docs.json`包括现行引用扫描与说明书链接。

## M8｜最终源码复审、负责人手测与PR交付停止线

- ChatGPT读取真实功能PR全部Java/SQL/YAML/脚本、迁移前后证据和最新head核对旧Kafka类已删、旧账本未改、三条Rabbit链全通、M0 B前置交付缺口已收口；**远端源码复审≠ChatGPT亲自执行用户环境**。
- 负责人在隔离 Portal+Console浏览器1280/375、历史日数据与订单/补投运维路径手测；若未做标`OWNER_MANUAL_NOT_EXECUTED`，不可写正式发布验收通过。
- Draft PR base必须是`docs/v12-m-rabbitmq-convergence`、功能分支`feat/v12-m-rabbitmq-only`；记录完整提交SHA及实际exitCode，不改PR#17或历史PR的head/base、不合并main、不进入C或D。停在M8等待复审。
- **通过判据**：当前项目源码/可执行包/现行Compose/配置/自动测试不依赖Kafka，真实无Kafka隔离环境跑V11旧月用量+V12-A新用量+B Commerce三链、旧历史Outbox追平且无双计、Rabbit断连/持卷恢复/Poison/重复投递均验证，旧用户资产未动。

## 唯一进度表（此时全部未实施）

| 阶段 | 任务 | 编码状态 | 计划证据 |
|---|---|---|---|
| M0 | B验收与Legacy链路/环境只读基线 | NOT_STARTED | `v12-m0-baseline.json` |
| M1 | DB切换fence、Legacy Rabbit与双Broker兼容模式 | NOT_STARTED | `v12-m1-legacy-rabbit.json` |
| M2 | 受控FROZEN切换、旧SENT/FAILED恢复与演练 | NOT_STARTED | `v12-m2-cutover.json` |
| M3 | Console Rabbit诊断、原REST/权限、中文页面 | NOT_STARTED | `v12-m3-console.json` |
| M4 | Kafka Java/Maven/Compose/脚本正式清退 | NOT_STARTED | `v12-m4-removal.json` |
| M5 | 无Kafka三链路真实E2E与浏览器 | NOT_STARTED | `v12-m5-kafka-free-e2e.json` |
| M6 | 故障与旧消息重复/混合版本边界 | NOT_STARTED | `v12-m6-failure-matrix.json` |
| M7 | Runbook、README/AGENTS/指南同步 | NOT_STARTED | `v12-m7-docs.json` |
| M8 | 远端代码复审与负责人手测 | NOT_STARTED | `docs/reviews/v12-m-*.md` |

**本文件是详细设计和验收标准，不是M阶段已实现或旧Kafka已经删除的声明。**
