# 本地Codex唯一执行入口｜V12-M 统一RabbitMQ并安全退役Kafka

> **本文件是开发提示词，不代表代码已实现。** 用户明确决定：V12-B完成后，FlowGate后续不再保留Kafka；必须让V11-C历史APP配额异步用量由Rabbit接管，保留MySQL历史账本和同一eventId，最后彻底移除当前Kafka代码/依赖/容器/配置。**V12-C抢购必须等M完成验收后再开始**。当前V12-B PR#17还Draft且存在历史测试证据未完整入仓的问题，M0必须先核实，不允许照PR描述虚构完成。

## 0. 先锁Git基线，按顺序阅读实际合同

1. `git fetch origin`，只读`git status -sb`、`git remote -v`、`git log -6 --oneline`，保证用户未提交修改/IDE/旧服务/本地个人分支不受影响。
2. **本轮设计分支**：`docs/v12-m-rabbitmq-convergence`，直接基于真实B代码分支`feat/v12-b-orders@c857d3b8532ae98dcfb208d10dd140efa0a3037b`，不是B旧文档分支。先核对远端最新SHA，若B期间有新的功能提交，停止并报告差异，不擅自rebase/覆盖。
3. 必须完整阅读：`AGENTS.md`当前最新段、`docs/reviews/v12-m-preflight-audit-2026-11-19.md`、`docs/design/v12-m-rabbitmq-migration-architecture.md`、**`docs/progress/v12-m-rabbitmq-convergence.md`（M0～M8唯一细分实施合同）**、`docs/design/v12-architecture-decisions.md`新增ADR-14、以及B的`docs/progress/v12-b-order-payment.md`与PR#17证据差异。旧ADR-13保留Kafka只对应历史过渡状态，**被新ADR-14替代**。
4. 本地以最新设计分支HEAD新建独立`feat/v12-m-rabbitmq-only`，后续只推此分支、Draft PR base=`docs/v12-m-rabbitmq-convergence`。绝不能改动旧PR #14～#17 base/head、合并main或重置/强推共享分支。每段Conventional Commits。
5. 仔细阅读实际源码：`QuotaChargeService`、`UsageGrantedEvent`、`UsageAggregationService`、`OutboxClaimService`、`OutboxEventEntryMapper`、`AsyncUsageReadMapper`、`LedgerIntegrityMapper`、`AsyncOperationsService`、`AsyncBrokerProbe`、`AsyncDiagnostics`、`OutboxPublisher`、`UsageEventListener`、`UsageKafkaConfiguration`、`UsageConsumerErrorHandler`、`UsageTopicRecovery`、`RabbitDelivery`、`ApiUsageWorker`、`CommerceWorker`、`sql/flowgate-v11.sql`与`flowgate-v11-c.sql`、`application.yml`、Control Maven、三个Compose、本地Python启动器与现行Console异步页面。**不能只按旧计划猜哪些文件依赖Kafka。**

## 1. 不可越界的基本原则

- MySQL V11权威旧账本`fg_quota_balance`、`fg_quota_ledger`、`fg_outbox_event`、`fg_consumed_event`、`fg_usage_daily`**数据不改、不清、不重算**；已有`USAGE_GRANTED`version1严格八字段和eventId/chargeId/UTC eventTime不变。V12新API用量和B Commerce已有Rabbit链路不改业务语义。
- **仅改变传输/运维，不改变Quota同步准入**。旧QuotaChargeService写Ledger/Outbox后成功返回，不等待Rabbit。Rabbit at-least-once，MySQL唯一键+事务保证幂等，不宣称Broker exactly-once。
- Java17、SpringBoot4、MySQL8.4、Redis7、Spring AMQP、Spring Cloud Gateway、原生ESM，不额外加微服务/Seata/CDC/Vue构建系统。复杂Java长方法写充分准确中文注释按`// 1.`、`// 2.`、`// 3.`。
- 绝对禁止碰用户原`.env`、IDE/JVM、常驻MySQL/Redis/Kafka/Rabbit容器和所有旧卷。**迁移演练仅使用新owner+随机端口+全新Compose/卷**，不得`down -v`、DROP/TRUNCATE旧表、`docker volume prune`、reset-hard、clean-fd或覆盖未提交代码。最终配置移除Kafka服务≠有权删除用户原Kafka卷。
- Kafka只允许在**过渡迁移演练的隔离环境**短暂使用；最终M成品源码/构建/配置/现行运行脚本和全栈测试都不能依赖Kafka。
- M0发现B业务缺陷、旧帐已不一致、旧实例清单不全或无法独占旧消息发布时，必须停止该子段并反馈具体阻塞；不要跳过或自动改账、合并PR、提前做C。

## 2. 严格按M0～M8阶段开发（每段同步Console/中文指南与真实证据）

**M0｜先验证B的交付与Legacy源码**
- 拉取真实PR#17及其B0证据；当前仅能从远端看到B0 JSON及3份失败记录，未见`docs/progress/v12-b-implementation.md`和`v12-b7-e2e.json`，B进度B1～B8仍NOT_STARTED；必须报告哪些B实际源码存在、哪些Java/Node/真实Rabbit E2E确实重新执行/可复核。如有必要在本M功能分支补充真实B定向证据，不能假称已有B完整验收。
- 全仓只读列出Kafka专用代码和实际部署环境/旧MySQL表及本机自有隔离实例，用**只读**完整性报表观察旧Ledger/Outbox/Consumed/Daily、SENT未消费、旧Consumer毒分区暂停/lag；失败先停止。记录`docs/progress/evidence/v12-m0-baseline.json`真实SHA和NOT_EXECUTED。

**M1｜最小DB fence + legacy Rabbit兼容实现**
- `sql/flowgate-v12-m.sql`只新增`fg_legacy_mq_cutover`一张小控制表，id=1、mode KAFKA/FROZEN/RABBIT、epoch、cutover_max_outbox_id/actor/time，真实新隔离库验证幂等DDL、DB CAS和审计；绝不删旧V11表。
- 新增`LegacyTransportFence`、`LegacyUsageRabbitConfiguration/Publisher/Listener`及必要的`LegacyUsageRabbitOperations`；旧Kafka发布者在M1兼容版本也必须受DB fence约束。Rabbit legacy单独`flowgate.usage.legacy` DirectExchange + quorum main/5秒TTL retry/Poison队列、可靠Confirm/mandatory return与COMMIT后basicAck。
- **直接复用**`UsageAggregationService.process`、原`fg_consumed_event(eventId,chargeId)`、原`fg_usage_daily`，原8字段Version1和eventId始终不变；新Listener包Jackson3 badJSON为明确永久异常，临时DB错误走确认后重试、未知最多12次入Poison。单节点quorum不是多节点HA。
- 实机测试M1仅一个有效发布路由/双Control，坏JSON和冲突Poison、已提交后ACK丢失重复仅累计一次；`v12-m1-legacy-rabbit.json`。

**M2｜可靠切换、历史SENT未消费恢复和回滚**
- 不能靠`FLOWGATE_KAFKA_ENABLED`与`FLOWGATE_RABBIT_ENABLED`两个独立flag实现切换。过渡阶段单一DB栅栏`KAFKA→FROZEN→RABBIT`+epoch CAS且ADMIN审计；**B旧版Control没有栅栏代码**，必须核对全量旧实例停止、在途Publisher/租约结束、Consumer真实退出再切，不能两边双发。
- 维护窗口FROZEN时Gateway仍可将旧配额Ledger+Outbox持久写入MySQL，异步统计标PENDING。旧Kafka事件已投递未消费（包括已SENT与毒分区滞留）的权威恢复以Outbox ID高水位+原eventId/chargeId/CRC/JSON指纹+旧Ledger验证为准，不按Kafka offset算消费结果。
- 旧NEW正常Rabbit领取；过期LEASED待租约清理；FAILED与`SENT AND NOT EXISTS fg_consumed_event`必须遵循旧300秒+进程启动冷却，ADMIN审计CAS分批<=100恢复原eventId，异常/孤儿/多event指纹冲突标INTEGRITY_GAP并停止。不调用QuotaChargeService补扣，也不重新生成EventUUID。
- 真实切换：旧进程全部退出→旧Kafka消费者可检查的最后排空或有理由的无法排空→FROZEN epoch CAS/高水位→RABBIT启用→原事件Rabbit消费→旧Ledger/Outbox/Consumed/Daily逐条和按天对账。验证旧消息迟到/双Control混合实例必阻断；可执行FROZEN安全回退练习。记录`v12-m2-cutover.json`，不能用“Rabbit队列为空”代替业务对账。

**M3｜运营Console、权限、中文帮助同步**
- 维持旧`/admin/usage`、`/admin/reconciliation`、`/admin/async-events`、`/admin/async-events/summary`、`retry/replay`原REST和ADMIN权限；将Kafka Admin、ConsumerGroup/PartitionPaused诊断改为真实Rabbit legacy主/Retry/Poison/活跃Consumer、quorum与切换栅栏状态/采样时间；数据显示V11_APP/V12_APP_API两来源不可混账。
- 原生ESM Console必要页面、空态/错误/恢复、中文指南与B Portal用户体验同步；真实1280/375/键盘/两账号权限；保存`v12-m3-console.json`。

**M4｜正式清理Kafka**
- 只有M2真实通过后才能删除：Control Maven`spring-boot-starter-kafka`、`spring.kafka`、所有Kafka Producer/Consumer/Topic/Offset代码/导入/Bean和`FLOWGATE_KAFKA_*`配置；`OutboxPublisher`改为Rabbit旧用量实现、`AsyncBrokerProbe/AsyncOperationsService`改为Rabbit；**旧QuotaChargeService/UsageAggregationService/历史数据不重做**。
- `compose.local.yml`、`compose.v11-demo.yml`、Rabbit overlay、`.env.example`、`v11_local_runtime.py`/`v11_local_core.py`、当前V11/V12/B smoke测试/旧Kafka CLI探针都同步移除Kafka依赖并换Rabbit确认；历史Kafka专用单测按实际情况迁移成Rabbit回归或移出现行构建，不篡改历史证据。
- 执行`mvn dependency:tree`、`docker compose config`、`rg`检查无Kafka当前运行引用；别在正式部署流程中留下一个隐藏Kafka Broker依赖。保存`v12-m4-removal.json`。

**M5｜真正Kafka-free三链业务联调**
- **没有任何Kafka容器/Kafka JAR/Kafka环境变量**的隔离实例，双Gateway真实V11旧APP月Quota Free5=5×200+1×429，旧Ledger/Outbox/Consumed/Daily一致、严格UTC按事件时间入日与隔日MATCH；V12-A API月计量Free5与Rabbit日报追平；B真实订单→模拟付款→Rabbit Commerce唯一Grant→APP/API月额度→Gateway调用/未使用退款与已用拒绝，2个普通用户权限隔离。
- V11/V12用量队列和Commerce三种事件不能互相误消费；Kafka-free`doctor`和现行自动浏览器1280/375/Console运维通过；保存`v12-m5-kafka-free-e2e.json`，严禁无测试预写PASS。

**M6｜异常矩阵，保留失败**
- 真Rabbit Legacy Broker停恢复/持卷重建、Confirm/Return、5s TTL retry、Poison、MySQL短断、ACK后重放100次、业务补投、历史Kafka积压通过MySQL原eventId恢复、双Control混合旧版被禁止；B Commerce与A usage复测不受干扰。
- 独立迁移演练轮次短暂用自有Kafka模拟历史积压**允许**，但Kafka-free最终全栈验收轮次必须没有它，两份证据分开。旧V11 B23/C26全历史故障若未重跑列NOT_EXECUTED，不把D压力实验偷带到M；保存`v12-m6-failure-matrix.json`。

**M7｜文档及安全迁移Runbook**
- `docs/guides/v12-m-rabbitmq.md`、`docs/guides/v12-m-kafka-retirement-runbook.md`、README、AGENTS、Console产品指南、V1.2路线图和示例配置同步表述当前项目只Rabbit。旧V11历史文档可以保留“曾使用Kafka”，不是当前依赖；**用户原Kafka卷仍必须保留**。
- Runbook写清楚只读健康检查、全部Control实例名单、维护窗口、旧消息恢复、切换CAS/rollback、验收、备份和必须人工授权的常驻环境操作；保存`v12-m7-docs.json`。

**M8｜停在Draft PR和真实复审**
- 按M0～M7各行填真实SHA/命令/退出码/通过失败跳过，形成独立`docs/progress/v12-m-implementation.md`、脱敏证据和截图，不能覆盖旧B证据；仅推`feat/v12-m-rabbitmq-only`且开Draft PR base本设计分支。
- 等ChatGPT远端源码+真实证据复审和负责人Portal/Console真人验收。**不合并main，不改PR#17，不进入V12-C高并发抢购/D性能**。负责人手测没有做须写NOT_EXECUTED。
- 如果在某一步发现迁移无法证明安全，优先FROZEN保留MySQL业务事实、让旧用量异步PENDING，按审计恢复，**不要用删除Kafka服务来掩盖旧统计缺口**。

## 3. 最终报告模板与完成判定

最终反馈至少包含：远端功能PR与SHA、M0 B真实证据是否齐全、M1单路由fence是否防旧Control混跑、M2旧SENT/FAILED的原事件恢复范围/缺口、Kafka客户端/Maven/Compose/config/脚本残余扫描、无Kafka环境真实三链业务HTTP与DB行数、Rabbit停恢复/毒消息/重放次数、用户资产未动证明、失败及NOT_EXECUTED项、负责人真人待验项。

**只有最终可部署源码/包与启动环境无需Kafka且旧V11和新V12业务均能实际跑通、历史授权统计不丢失不双算，才叫V12-M交付候选。设计文档、Kafka容器停止、或Maven依赖移除中的任何单项都不等于迁移完成。**
