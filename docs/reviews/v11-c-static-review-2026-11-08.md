# FlowGate V11-C｜远端源码与集成证据复审（2026-11-08）

> **复审起始实际HEAD**：`08c48d015d320d7714fb8c9e70d09f8a5814de04`；分支`feat/v11-c-async-usage`，Draft PR #9 → `feat/v11-b-quota`。本轮ChatGPT读取GitHub远端真实Java/SQL/JS/Compose、Codex进度及脱敏证据，**未独立运行Java17、Kafka、MySQL、Redis、Docker或真人浏览器**。下文Java67与26组C6真实HTTP/故障场景来自**Codex实际运行记录+仓库证据/脚本的可核查内容**，不是本轮独立重跑的数字。
> **结论**：C0～C6源码与实测证据已远端落地，主链路设计正确：Quota权威MySQL事务不等待Kafka，Outbox短事务租约、Kafka至少一次投递、ConsumedEvent+UsageDaily同事务和offset后置ACK均在代码中实现。**但3项恢复/对账边界缺少保护（P1-C01～C03），建议C定向修补后再进入D**。这3项是静态审查发现的**额外未覆盖场景**，不是宣称原26组测试失败、当前已观测到109笔账本不守恒。

## 1. 实际交付核验

| 子块 | 关键真实代码 | Codex现有证据 |
|---|---|---|
| C0 | B两项小修先在真MySQL双Gateway复测，旧A同月分段/冻结余额409与OFF紧急撤销 | `evidence/v11-c0-b-fix-smoke.json` passed=true，23条场景摘要；原B证据保留 |
| C1 | `flowgate-control/pom.xml`仅Control加Boot4 Kafka starter、`sql/flowgate-v11-c.sql`两表、Async默认OFF/Compose可选mq、8字段事件DTO | `evidence/v11-c1-smoke.json`；Maven/DDL二次真库及无Kafka配额准入 |
| C2 | `OutboxClaimService`短事务`SKIP LOCKED`批次20/30秒lease，`OutboxPublisher`事务外Kafka ACK再条件SENT，退避/FAILED/CAS | `evidence/v11-c2-smoke.json`；双worker/旧owner更新0 |
| C3 | `UsageAggregationService`先核对本地Outbox+Ledger再唯一ConsumedEvent及Daily UPSERT同事务；`UsageEventListener`业务事务提交后才ack；无DLT时保留offset/暂停毒分区 | `evidence/v11-c3-smoke.json`；100并发重复仅1，DB回滚无孤标，UTC日按Outbox |
| C4 | `UsageController`和`AsyncOperationsService`、`AsyncUsageReadMapper`：真实运营API，RR只读快照、三元组游标、审计/ADMIN重试 | `evidence/v11-c4-smoke.json`；PENDING→MATCH→MISMATCH、同日多App分页 |
| C5 | `static/console/usage.js`与导航/指南，真实API用量/异步事件/对账；SENT只代表Broker已确认 | Node真实ES模块+模拟DOM通过，真人1280/375px/键盘未做 |
| C6 | `scripts/v11-c-smoke.py`真MySQL8.4/Redis7/2 Gateway/Kafka4.1.1，实际Broker ACK后`halt(23)`、DB提交后`halt(24)`、offset回放/毒分区/SQL故障/混合身份 | Codex记录Maven**67 JUnit，失败0/跳过0**、6套Node通过、C6**26组**通过；最后**109 Ledger=109 Outbox=109 Consumed=109 Daily grant_count**；C OFF验收通过 |

**事实限制**：历史`docs/progress/v11-c-async.md`最后的Codex `git push` HTTP403和其当时“远端仍只有设计”是该机器**当时**的真实日志，保留不可删除。复审时GitHub远端已经包含完整C源码/证据，PR #9仍显示过时的“待编码”描述，必须更新为“已实现、待复审收口”。当前没有自动合并main或任何Stacked PR。

## 2. P1-C01｜Kafka容器重建或记录丢失后的SENT未消费不可恢复

### 真实源码与场景

- `compose.local.yml` Kafka服务声明`KAFKA_LOG_DIRS=/tmp/kraft-combined-logs`，**没有volumes挂载**，与同文件MySQL/Redis命名卷不同。重建/删除该Kafka容器会丢失容器可写层中的Kafka日志、Topic/offset（区别于同一容器简单stop/start）。
- `OutboxEventEntryMapper.sent`在Broker ACK后直接标记`SENT`；`claimable`只领取NEW或过期LEASED；运营`AsyncUsageReadMapper.retry`只允许`FAILED→NEW`。若Broker已ACK、消费者还没完成MySQL聚合就遇容器重建/日志清空或Kafka保留期清除，该`SENT`的授权事件不会再次投递，`fg_consumed_event`缺失且`fg_usage_daily`长期PENDING。**B的Ledger/Outbox事实仍在，不是Quota超扣或真正丢失账本，但C读模型无法追平**。
- 已通过的C6测试覆盖**停机/恢复同一Broker**、ACK与DB回写之间崩溃、offset重放；**没覆盖Broker容器删除重建/消息因retention消失后SENT尚未消费**。所以不能拿原26组证明这条路径安全。

### 最小修复建议

1. 本地Compose Kafka日志使用新命名卷`kafka-data:/tmp/kraft-combined-logs`（需先核对Apache Kafka4.1.1镜像可写权限/KRaft集群ID稳定重启）。**只创建新卷/演示环境测试，不删除用户已有数据库或Compose卷、不擅自动真实Kafka数据**；README明确Broker仍单节点无生产HA。
2. 增加**受控SENT未消费补投**：ADMIN-only安全操作或定时限额扫描`SENT`且`NOT EXISTS fg_consumed_event(eventId)`的超出合理gracePeriod事件，**保留原eventId与payload**，条件CAS重排给Outbox Worker，并写审计。严禁复写新的Ledger/QuotaBalance或将正常未消费尚在Kafka排队中的消息认定为权限错误。仅当有可靠性必要时提供一个专用重排入口；不要误改现有FAILED状态语义。
3. 隔离实测：Kafka容器`down`/`up`但保留新命名卷，数据和offset仍在；在**仅本次自建Lab**内模拟Broker记录已经消失（无权清理用户卷），保留B Ledger+Outbox SENT→无ConsumedEvent→经ADMIN补投→ConsumedEvent/Daily恢复1次，无第二笔Grant。两个ADMIN同时发起重排只有一条成功，原eventId不变。

**优先级P1**：C声称可靠的**异步统计可恢复**之前需收口。只增加命名卷而完全没有SENT重放手段仍存在保留期/盘故障恢复缺口；只加重放而没有持久卷则正常`compose down/up`就会人为制造故障。

## 3. P1-C02｜消费者把短暂MySQL故障也永久暂停成毒消息

### 真实源码与场景

`UsageKafkaConfiguration.usageKafkaFactory`的自定义`CommonErrorHandler.handleRemaining`**不区分异常类型**：对于消费过程抛出的`DataAccessException`、短暂MySQL不可用、锁等待超时，与`INVALID_USAGE_EVENT`/本地Outbox-Ledger冲突一律`seek`并`container.pausePartition`。注释表示需“负责人重启消费者继续原offset”。C6已验证数据库表故障后**重启**Consumer可重放，但尚未覆盖“DB自动恢复、Control不重启、消费自动恢复追平”。

短时MySQL抖动由此可使一个分区长期停住，即使Broker/MySQL都恢复、后面事件正确；如果多个分区先后异常，读模型可能长期积压且需要人工重启。这是**可恢复故障被按永久毒消息处理的运维缺口**，并非已观测到双计数。

**最小修复**：利用已使用的Spring Kafka容器/错误处理机制对**临时数据库/网络异常**做有界退避+自动恢复同一offset；不可恢复的协议格式错误、eventId/chargeId冲突保留原“暂停问题分区、不ack、不虚假DLT”策略。重试有上限、不得忙等与无限刷屏；持续不可达可周期性低频再试或给出清晰告警与安全恢复动作。拒绝“异常catch后直接ack”或静默跳offset。

**必须实测**：消费中途短暂断MySQL后恢复，不重启Control/Consumer，事件最终Consumed+Daily并原offset确认；永久不合法event仍暂停/不ACK，其他分区继续；Maven/真Kafka真实日志和偏移证据。

## 4. P1-C03｜权威Ledger缺少Outbox时对账内连接会漏报

### 真实源码与场景

`AsyncUsageReadMapper.Sql.reconciliation()`构造的权威expected子查询是：
`FROM fg_quota_ledger l JOIN fg_outbox_event o ON JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId'))=l.charge_id ...`（**INNER JOIN**），外加Daily模型UNION。若发生历史损坏、非法人工删除、异常迁移造成**Ledger有授权但对应Outbox事件缺失**，该权威Ledger行直接从expected中消失；当也不存在Daily行时API可能返回**空列表**而不是诊断错误。存在多条Outbox都声称同一chargeId的异常时，又可能重复放大expected。与“Ledger是权威授权事实、对账必须揭示数据缺口”的业务定义矛盾。

正常B交易的Ledger+Outbox同事务可避免这类新写入，不意味着升级/历史数据或运维故障永不产生孤单行。本次C4测试只覆盖**已有正确Outbox时**的PENDING/MATCH/MISMATCH，没有注入孤儿Ledger/重复Outbox。

**最小修复**：
1. 对账显式检测Ledger→Outbox缺失、Outbox→Ledger缺失/一笔charge被多Outbox指向的异常。不能因为缺失事件时间就悄悄按Ledger.created_at当作事件UTC日并返回MATCH；日期来源不确定时标`INTEGRITY_GAP`或单独`integrityWarnings`/诊断摘要，且绝不作为MATCH。按有界日期/应用条件过滤，避免一条坏数据迫使全表扫描成为默认管理页开销。
2. 本地正确事务仍按Outbox.eventTime分组；已有三态PENDING/MATCH/MISMATCH保留，**新完整性异常状态需前端中文解释**。没有该事件时因缺失准确日期，可用独立预检/健康摘要提示“账本缺少事件时间无法按日归属”，不伪造日报归属。
3. 真MySQL隔离测试：先正常一Ledger+一Outbox→PENDING/MATCH；然后只在独占Lab中删对应Outbox→管理对账显式报告权威完整性异常，不能空列表/0；另插重复Outbox关联同charge→不得把1次Grant算成2次权威授权；未修改真实余额/历史Ledger。

**优先级P1**：这是**对账审计的异常可见性**而非正常MySQL扣额出错。维护“失败可诊断”是项目C阶段的实际价值，建议C完成前收口。

## 5. 其他非阻断限制（P2/后续D）

- Kafka单KRaft Broker+副本1，不等同生产HA；消息保留与单Broker磁盘损坏风险需清晰说明。C可演示至少一次+DB幂等，但不宣称跨Kafka/MySQL的分布式exactly-once。
- 不可恢复毒消息目前保守暂停分区、不ACK，不提供自动确认DLT或安全跳过；这是明确的安全取舍。正式运营应补足可定位状态、人工运行手册与最小告警，不要把重启视为已修复错误事件内容。
- `AsyncUsageReadMapper.reconciliation()`的`JSON_EXTRACT(payload_json,'$.chargeId')`联接缺结构化索引，且`AsyncUsageReadMapper.summary()`会对Outbox做累计扫描。10万级表规模、`EXPLAIN ANALYZE`、读模型重建/查询效率和四组公平性能比较由**V11-D**进行，不能把C6的109笔演示视作规模测试。
- 用户**真人**浏览器1280px/375px、ADMIN/OPERATOR/VIEWER完整路径、Tab/ESC与前端视觉仍未验收；旧V1 Caller历史列表、CB修订状态等已知限制没有被Kafka自动解决。
- 生产MySQL增量SQL、Kafka持久数据卷或Async显式激活尚未由本轮ChatGPT改动用户本地环境。即使C技术源码已交付，也不意味着正式生产就绪或main已合并。

## 6. 当前结论、Codex派工及Git门槛

**阶段判定**：C0～C6主要功能**已实装，Codex验收证据较充分**，可以进行定向P1收口和负责人真人产品验收；**不建议现在宣布C全量可靠性验收完毕并直接开始D或合并main**。本轮ChatGPT仅出具源码静态复审/修复任务，不把三项修复称为已经编码。

当前唯一Codex交接：`CODEX_V11_C_REVIEW_FIX_PROMPT.md`。在**现有`feat/v11-c-async-usage`**小步修3项边界，增真实MySQL/Kafka集成测试、新脱敏结果`docs/progress/evidence/v11-c-review-fix-smoke.json`，原C0～C6和B历史证据完整保留。修复测试通过后再交ChatGPT复审和负责人浏览器验收。仍保持PR #9 Draft/PR #8 Draft，不合并main、不进行D长压测。

**注意**：历史Codex `git push` HTTP403仅反映当时，审核起点的GitHub已真实有完整C源码与证据，不能因为这个日志强推或重置现有分支。
