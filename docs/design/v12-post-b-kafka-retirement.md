# FlowGate｜V12-B之后统一RabbitMQ、退役Kafka设计（2026-11-17）

> **决策状态：已确认的后续架构要求，尚未实施或验收。** 负责人明确要求“等V12-B完成后，后续不再保留Kafka”。本方案在**V12-B真正交付并复审后**实施，不改变已派发的V12-B编码任务，不自动操作现有Kafka数据卷，也不把B期间仍运行Kafka视为违反约束。权威决策见同分支 v12-architecture-decisions.md 的 ADR-14。
>
> **最终目标**：FlowGate在V12后续版本的运行依赖、业务事件发布/消费、测试启动器与当前技术栈说明中不再要求、启动、引用Kafka；全部消息业务统一RabbitMQ。历史V11 Kafka阶段的设计、SQL和已脱敏测试证据属于项目发展记录，不抹除、不伪造成Rabbit实测。

## 1. 明确迁移哪些东西，不迁移哪些东西

| 业务链路 | B结束时的真实架构候选 | 迁移后目标 |
|---|---|---|
| V11 APP月度授权用量 | MySQL fg_quota_ledger + fg_outbox_event → Kafka publisher/consumer → fg_consumed_event、fg_usage_daily | **保留原有权威MySQL表和V11事件业务语义**，发布与消费替换为RabbitMQ legacy-usage独立持久交换机/主队列/TTL重试/Poison |
| V12 APP/API月度用量 | MySQL新Ledger/Outbox → RabbitMQ usage.v12 | 原样保留，不因V11迁移重复发布或合并统计表 |
| V12普通订单、模拟付款及权益履约 | MySQL BusinessOutbox → RabbitMQ commerce | 原样保留 |
| V12-C抢购活动事件、库存补偿 | 未开发，未来复用RabbitMQ commerce | 只能RabbitMQ，不允许再引Kafka |
| 配额实时授权 | MySQL权威Balance/Ledger，在Control事务内决定 | **完全不改**，Rabbit只负责异步投递/统计，不参与是否允许请求 |
| 旧Ledger/Outbox/Consumed/Daily历史记录 | MySQL现有持久表 | **原样保留**，不能删除重建或从Kafka offset猜测替代它们 |

目标Rabbit建议使用 **独立legacy-usage交换机及队列**（名称由实现合同冻结，例如 flowgate.usage.legacy），不能把V11严格旧事件直接投进V12 usage消费者的不同Schema队列，也不能把订单消息投进用量队列。Rabbit Broker可共用，但业务Consumer/Retry/Poison与监控隔离；MySQL使用原V11 Outbox、Consumed去重及Daily授权统计，不新建与其语义重复的账本。

## 2. 可靠切换流程：MySQL事件事实优先

### M0 B阶段交付门槛

- V12-B源码、定向测试和B0三项P1修复必须已提交并复审，B Commerce Rabbit已可运行；否则**不能抢先删Kafka**。
- 核对旧V11发布者、Listener、重试错误策略、手工SENT重排、异步开关和对账SQL的实际行为；记录当前Ledger→Outbox→Consumed→Daily关系与版本事件Schema。
- 只在隔离随机Compose/新的MySQL8.4、Redis7、Rabbit、Kafka数据卷进行演练，未获明确授权不得对原用户常驻环境关停Kafka、修改.env或删除任何卷。

### M1 新Rabbit消费者与兼容读取

- 为V11 USAGE_GRANTED保持**原eventId、chargeId、事件版本、权威Ledger与Outbox核验、UTC日归属、Consumption去重主键**，新建Rabbit专用Publisher/Listener/拓扑与独立可追溯队列。
- 旧V11 UsageDaily、V11只读对账、原Ledger授权不做破坏性DDL；Rabbit消费事务先重查V11 MySQL Ledger/Outbox，写同一fg_consumed_event及原fg_usage_daily，成功COMMIT后Rabbit手动ACK；消费重复同eventId不二次累计。
- Rabbit可靠策略与B0同级别：publisher confirm+mandatory return、Outbox lease/CAS/有界自动退避、长期SENT未消费受控重排原eventId、临时DB问题持久TTL retry、永久坏消息Poison、未知错误有界重试及监控，不承诺跨系统 exactly-once。
- **只有一个活跃V11 Outbox发布路由**（Kafka或Rabbit）；不能无协调地“同时发布Kafka和Rabbit”，也不能仅靠消息源两边幂等就放弃切换记录。

### M2 离线演练及切换一致性

- 在模拟历史用量和新调用并存的隔离环境，给Kafka/V11原Consumer一个可观察的处理窗口：暂停V11 Kafka新增投递、旧Kafka Consumer完成可见队列消费；检查真实MySQL每笔Ledger可映射Outbox，以及fg_consumed_event/fg_usage_daily是否追平。
- 对Kafka消息已经发布但未消费的情况，**不靠删除Kafka Topic或直接从新Queue猜测位置**：按V11 MySQL Outbox+Ledger以及ConsumedEvent查缺口，使用原eventId与受控Outbox恢复/重排在Rabbit消费完成；必须核查重复事件不会放大日统计。
- Kafka publisher lease/已发送但未确认的消息先等待租约和重排窗口，切换开关采用可审计且默认拒绝误双投的单一broker模式，防止双实例一方Kafka一方Rabbit同时发布。混合Control二进制/开关未知时不得接流或发旧用量。
- Rabbit就绪后切换新V11消息投递为Rabbit；保持Gateway业务授权可正常产生MySQL Ledger/Outbox，允许异步统计短暂滞后但不可失去追偿事实。迁移完成要求按Ledger/Outbox/Consumed/Daily真实核对一致、已受理未消费清零或拥有明确可恢复待办，不能仅观察Rabbit队列为空。
- 若切换前失败，回退到**已验证的旧V11 Kafka路径**并保留同一MySQL事件ID、不得重复统计；如果已切至Rabbit，则按实际持久事件及幂等状态评估是否需要回滚，**禁止简单更改配置开关后同时启动两个发布者**。

### M3 从代码、依赖与运行环境正式移除Kafka

- 当全部V11使用量已经经Rabbit正常处理、隔离故障恢复及旧V11业务回归通过后，在**新的后续功能分支**删除Kafka专用发布/监听/offset/recovery源代码、Kafka Maven依赖、相关application配置、当前启动器与Docker Compose中的Kafka运行服务/健康依赖/环境变量，并替换与Kafka绑定的现代自动测试与启动说明。
- 只保留**历史Git提交、历史V11阶段文档与真实旧测试证据**，不删历史MySQL表/事件ID/审计记录，也不冒充历史上从未使用过Kafka。
- 旧Kafka测试探针等源文件可保留在历史Git仓库，不应在**现行构建与交付路径**仍要求Kafka Jar或Kafka服务；现行主文档明确技术栈只Rabbit，历史说明注明“V11曾使用Kafka，现已退役”。
- **绝不执行**未经授权的 docker down -v、rm Kafka用户容器/持久卷、DROP/TRUNCATE旧表、重置consumer offset；退役现有常驻Kafka实例需要负责人单独授权和事前备份。删除新交付运行依赖与破坏用户旧资产是两回事。

## 3. 性能、可靠性与测试硬线

必须在独立随机环境、真实MySQL+Rabbit、**不启动任何Kafka Broker**的状态下进行最终回归：V11 Free5授权5成功+1拒绝，MySQL V11 Ledger=Outbox=Consumed=Daily，重复历史事件100次仅累计一次，旧“昨天封账”MATCH而今天仍PROVISIONAL；V12-A用量和V12-B Commerce原样正常，Portal/Console显示两种用量来源正确。

必须真实注入：Rabbit停恢复、发布Confirm失败/mandatory return、持卷队列恢复、暂时DB失败后的Rabbit重试、格式坏消息Poison、Kafka中遗留SENT未消费经MySQL恢复、同一eventId在迁移边界前后重复消费、双Control切换时单发布路由、消费者COMMIT后丢ACK、Rabbit超过等待期受控补投，以及历史两类账本对账不双计。隔离证据记录SHA、容器owner、版本、事件计数、实际exitCode和失败尝试，未执行写NOT_EXECUTED。

交付停止线：**执行一次“Kafka Broker完全不存在”的全栈启动**，无Kafka镜像、无Kafka客户端依赖、无Kafka环境变量、无Kafka Listener/Publisher在当前运行路径；同时确保所有历史MySQL业务事实仍完整、V11与V12用量可追溯。若任何旧Kafka-only配置仍影响启动、配额或统计，本阶段不得标完成，也不得进入V12-C高并发抢购。

## 4. Git/阶段安排

建议在V12-B真实完成后的**独立前置阶段 V12-M（MQ收敛）**实施：A→B→**M**→C→D。M不是B0的延期工作，B0只修好现有A Rabbit；M负责**旧V11-C Kafka → Rabbit的真正迁移/退役**。Codex详细任务书只在B通过复审之后生成，先分离源码审计/迁移验证/正式删依赖几步，不要让Codex一个提交同时改历史账本和删Broker。

**当前文件只是迁移设计，不是已实施；既有V12-B订单执行合同照旧，不要求B阶段自己删Kafka。**
