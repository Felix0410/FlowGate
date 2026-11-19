# FlowGate V12-M｜Kafka退役前远端源码审计（2026-11-19）

> 事实基线：`feat/v12-b-orders@c857d3b8532ae98dcfb208d10dd140efa0a3037b`，PR #17 Draft，功能已提交但未独立完整验收；本轮仅通过GitHub读取源码/PR元数据/现有证据，**没有运行用户本机的Java、MySQL、Rabbit、Kafka或浏览器**。M阶段只是设计，不是已迁移。

## 1. 分支与证据事实

- PR #17 `feat/v12-b-orders` → `docs/v12-b-order-design`，最新功能提交`c857d3b`，内容含B0修复、订单/收据/Rabbit Commerce/退款代码与SQL；当前仍Draft、不合并。
- 2026-11-19盘点：功能分支相对`docs/v12-b-order-design` **ahead 2, behind 5**。后5个设计提交包含`docs/design/v12-post-b-kafka-retirement.md`和ADR-14。M设计从**实际最新B代码HEAD**单独建`docs/v12-m-rabbitmq-convergence`；只把最新MQ方向和计划带入本分支，**不改B功能PR base/head、不rebase或强推旧PR**。
- PR #17描述声称本地Java95项不同测试、Node9、Python14及双Control双Gateway、100支付、100重复投递、Rabbit故障及浏览器通过；但GitHub树上只找到`docs/progress/evidence/v12-b0-rabbit-fixes.json`、`v12-b0-console.json`和3份B0失败记录，**未找到承诺的`docs/progress/v12-b-implementation.md`和`v12-b7-e2e.json`**；`docs/progress/v12-b-order-payment.md`的B1～B8仍显示NOT_STARTED。这是证据和文档交付缺口，不能因此断言代码不存在，也不能用PR描述等同于可复核证据。
- B0 Rabbit真实隔离证据记录Rabbit3.13.7、`stream_queue=enabled`、quorum at-least-once DLX/overflow reject-publish、坏JSON Poision、DB暂停→TTL、Broker停恢复、原卷固定hostname重建和mandatory return；旧Console无V12表DISABLED通过。**提交的证据由Codex执行记录，并非本轮重复实测**。

## 2. V11旧链路真实代码路径（M需要处理）

| 位置 | 当前事实 | M处理 |
|---|---|---|
| `QuotaChargeService` | `fg_quota_balance`、`fg_quota_ledger`、`fg_outbox_event`在一个MySQL授权事务提交；事件`USAGE_GRANTED`八字段且附原eventId/chargeId | 业务授权不改，原事件Schema和eventId保留 |
| `async/UsageGrantedEvent.java` | 严格8字段，version=1，`eventTime` UTC日归属和规范化SHA | 保留并加强Jackson3坏JSON分类，Rabbit仍使用完全相同事件 |
| `service/OutboxClaimService.java`与`mapper/OutboxEventEntryMapper.java` | MySQL SKIP LOCKED、30秒LEASED、尝试上限8、NEW/LEASED/SENT/FAILED、CAS owner；SENT以原`next_attempt_at`为后续审计依据 | 可保留不变的MySQL租约逻辑，M阶段增加持久单路由fence与受控旧SENT清算；不可仅改发送客户端 |
| `service/OutboxPublisher.java` | `KafkaTemplate`发布后才SENT，异步最多100在途，send future ACK | 替换为Rabbit Publisher，confirm+mandatory return才SENT、租约/回执延迟安全 |
| `listener/UsageEventListener.java`、`async/UsageKafkaConfiguration.java`、`async/UsageConsumerErrorHandler.java`、`async/UsageTopicRecovery.java` | Kafka Listener manual offset ACK、旧Kafka分区暂停/退避与Topic自动恢复 | 实现Rabbit quorum手动ACK、持久retry/poison后从现行构建移除Kafka专用类 |
| `service/UsageAggregationService.java` | `fg_consumed_event.event_id PK`且`charge_id UNIQUE`，Outbox+Ledger验证，`fg_usage_daily`同事务UPSERT，提交后才能ACK | **原样复用**，这是切换边界抗重复的权威核心；不改写旧账本 |
| `service/AsyncOperationsService.java`、`async/AsyncBrokerProbe.java`、`async/AsyncDiagnostics.java` | 运营页依赖Kafka Admin和Listener分区、暂停/offset状态与SENT冷却300秒 | 切换为Rabbit真实主/重试/poison队列诊断、Consumer活性、旧Outbox补投与审计，保留现有REST/ADMIN权限 |
| `application.yml`、`flowgate-control/pom.xml` | `spring-boot-starter-kafka`、`spring.kafka`、`FLOWGATE_KAFKA_*`、旧Topic/group；已有AMQP starter | 最终删Kafka依赖/配置/env，继续使用Spring AMQP |
| `compose.local.yml`、`compose.v11-demo.yml`、`compose.v12-a.yml` | local/demo仍含Kafka容器，Rabbit作为A附加overlay；用户旧Kafka卷曾持久存在 | 最终**当前隔离部署**只用MySQL/Redis/Rabbit，更新启动器而非销毁旧用户卷 |
| `scripts/v11_local_core.py`、`scripts/v11_local_runtime.py`、`scripts/V11Kafka*.java`、`scripts/test_v11_local.py` | doctor依赖Kafka topic/group、运行器拉Kafka、使用旧Kafka CLI探针 | 新doctor检验真实Rabbit协议/队列Consumer、保护owner+随机卷；旧Kafka探针归档/从现行执行入口淘汰 |

## 3. 数据库真实约束及陷阱

- `sql/flowgate-v11.sql`：`fg_outbox_event(event_id UNIQUE,event_type,payload_json,status,next_attempt_at,attempt_count,lease_owner,lease_until,last_error_code)`；旧事件表**不包含Kafka offset或独立broker身份**。现有`OutboxEventEntryMapper.sent()`写`next_attempt_at=UTC_TIMESTAMP(3)`，旧`AsyncUsageReadMapper.replay()`和`AsyncOperationsService.replay()`要求超过300秒+进程启动冷却。M切换不能凭`status=SENT`推断已被统计，必须检查`fg_consumed_event`。
- `sql/flowgate-v11-c.sql`：`fg_consumed_event.event_id PK`、`charge_id UNIQUE`与`fg_usage_daily(day_utc,application_id,api_id)`主键。**原样保留**，不要换表、清空日报、按Kafka timestamp重新算日归属。
- 旧`OutboxClaimService.claim()`只领取`NEW`或租约过期`LEASED`且`attempt_count<8`；`FAILED`不自动领取。M切换时对SENT未消费/FAILED未消费必须明确审计和限量恢复，不能误判会自动追平。
- 旧`UsageGrantedEvent.parse()`直接调用Jackson3 `readTree`，边界异常可能不符合Rabbit永久错误分类；新Consumer必须包装永久JSON异常，且不能接入V12新API用量的另一种payload。
- 旧Kafka`UsageConsumerErrorHandler`永久毒消息处理为暂停分区、不commit offset；切换时积压/暂停区的全部未消费事实仍可由权威Outbox+Ledger恢复，但必须先有一致性扫描与单发布路径栅栏。
- 用户期望Kafka彻底退役意味着**运行态+构建依赖+当前测试/Compose/文档都不再需要Kafka**，但不能删除历史Git记录/历史V11证据和用户旧Kafka卷。

## 4. 本设计关注的最小风险与停止线

1. **混合实例**：只给新Rabbit发布者加feature flag，而任留旧Control Kafka发布者运行，会产生双链并存；M0必须盘点所有Control实例，切换期间有可验证的全实例停写/单DB fence/epoch，不允许旧二进制绕过。不能声称无停机灰度可随便切。
2. **SENT悬空**：Kafka Broker ACK后Consumer未写`fg_consumed_event`，可能积压/暂停毒分区；先停旧异步后据MySQL缺口补发**原事件**Rabbit，不能直接删除Topic或根据Rabbit队列深度宣布完成。
3. **进程中途死亡**：Outbox租约和confirm不确定会重复投递，利用旧`event_id`及`charge_id`唯一、消费同事务写日聚合抗重复；不要伪造exactly-once delivery。
4. **数据健康**：预先已有的孤儿Ledger/Outbox、缺失事件或冲突指纹必须报`INTEGRITY_GAP`并阻断迁移，不自动改写权威流水。
5. **人为权限和保护**：所有测试只新建随机owner的容器和卷。原用户.env/.local/IDE、Kafka/MySQL/Redis/Rabbit实际卷只读；没有授权绝不停止或清理用户现存Kafka容器/卷。

**审计结论：可开始V12-M的详细方案设计，不能开始全局Kafka删除。** B的真实最终验收与上述证据缺口作为M0前置门槛，不能用本设计跳过。
