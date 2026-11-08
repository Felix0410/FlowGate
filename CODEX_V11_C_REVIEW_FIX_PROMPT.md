# V11-C远端源码复审后的定向修复任务｜Codex唯一当前入口（2026-11-08）

> **事实起点**：V11-C的C0～C6**真实代码已提交远端**，基线审核起点`08c48d015d320d7714fb8c9e70d09f8a5814de04`，PR #9 → PR #8（Draft）。Codex历史记录显示67JUnit和26组隔离Kafka/HTTP通过、109 Ledger/Outbox/Consumed/日授权计数守恒；**ChatGPT本次未重新在本机运行这些测试**。不重做C0～C6，不开发D，不合并main。
> **唯一新增审核报告**：`docs/reviews/v11-c-static-review-2026-11-08.md`。本轮只修报告中的P1-C01/C02/C03三项并增加**未覆盖的真实故障复现与回归**。保留原C0～C6/B/A全部证据不覆盖，原Codex历史push403是它当时的记录，当前GitHub远端已真实有C源码。

## F0：安全基线与工作顺序

1. `pwd; git status -sb; git fetch origin; git branch -vv; git log -1 --oneline`，只在`feat/v11-c-async-usage`编码。若本地未提交/同名旧分支/远端SHA变动，保护已有工作树先对比，不`reset --hard`/`clean -fd`/强推/改PR base/合并main。
2. 阅读`docs/reviews/v11-c-static-review-2026-11-08.md`、`docs/design/v11-c-async-execution.md`和`docs/progress/v11-c-async.md`最新C-09实际验收，重点源码：`compose.local.yml`、`OutboxEventEntryMapper`、`OutboxClaimService`、`OutboxPublisher`、`UsageKafkaConfiguration`、`UsageEventListener`、`UsageAggregationService`、`AsyncUsageReadMapper`、`AsyncOperationsService`、`UsageController`、`static/console/usage.js`。
3. 全部MySQL8.4/Redis7/Kafka4.1.1/双Gateway测试只在新随机端口和**独立容器/独立Kafka卷**的隔离Lab。禁止`docker compose down -v`用于用户现有项目，禁止迁移/删除用户旧MySQL/Redis/Kafka日志、.env、IDE、后台JVM。**修改compose文件不等于允许重启用户正在运行的Kafka**，新卷与原数据迁移必须由负责人单独授权。

## F1｜P1-C01：本地Kafka日志持久化 + SENT未消费恢复

**反例需先确认**：现`compose.local.yml` Kafka配置`KAFKA_LOG_DIRS=/tmp/kraft-combined-logs`却无volume；容器**删除/重建**（非同容器stop/start）后，已经Broker-ACK并标SENT但未消费的数据可能不在Broker，且现人工`retry`只支持FAILED，Outbox不会自动重发。

1. 在Compose仅新增`kafka-data`命名卷，挂载到**真实镜像使用的KRaft log目录**，保留已存在MySQL/Redis服务和挂载原样；在**本轮独立测试项目名**下验证Kafka4.1.1 volume目录属主权限、KRaft元数据重启读取与topic/offset不丢。不能从测试成功推断单Broker生产HA。
2. 新增**ADMIN-only、可审计且有CAS条件的SENT未消费补投**：仅`status='SENT'`且`NOT EXISTS fg_consumed_event`、超过可配置合理gracePeriod时，保留原`eventId`和原payload，重新排为NEW供Worker正常发送；不允许重新产生QuotaLedger/Charge/新eventId，不对正在正常消费的最近SENT做高频自动重排。建议沿用`POST /admin/async-events/{id}/replay`或者明确`retry`的第二个受限动作，但前端必须与现有FAILED重试语义区分；OPERATOR/VIEWER永远403。
3. 加入一个受限有界的异常告警/统计（例如SENT却未消费且大于grace的数量），产品后台给出“Kafka确认但尚未消费；可补投原事件”，没有Kafka可靠存在时不能说“已统计”。
4. 真实测试**两条分别验证**：A) Kafka独立Compose`down`（不用`-v`）后重新`up`，持久卷保存topic与offset；B) 仅在全新隔离Lab模拟Broker原记录彻底丢失但MySQL Outbox=SENT、Consumed不存在，ADMIN补投原eventId后只形成1条Consumed/Daily、不新增Ledger/余额消耗。双ADMIN同时补投仅1个200，其余409；已有正常SENT已消费不可补投。

## F2｜P1-C02：短暂数据库失败不应永久暂停消费分区

**反例**：`UsageKafkaConfiguration`目前自定义`CommonErrorHandler`对任何异常（包括MySQL连接丢失/临时锁超时）都seek并pause分区，只有手动重启才恢复。C6覆盖了DB故障后**重启Consumer**，没有覆盖DB恢复后**不停机自动追平**。

1. 用现有Spring Kafka标准错误处理能力最小改动：将暂时的`DataAccessException`、SQL连接断开/瞬时超时等与永久无效事件`INVALID_USAGE_EVENT`、`EVENT_CONFLICT`、`CONSUME_DUPLICATE_CHARGE`分开。包装异常需要正确unwrap root cause，避免看顶层异常名一律按毒消息。
2. **暂时故障**：有界退避和低频自动重新尝试同offset，可自动恢复且不忙循环、不提前ACK。长期故障可保持清晰报警及有界周期再试；需要实现恢复机制，不可把永久停止当作“已重试”。
3. **不可恢复毒消息**：继续维持原`seek`/仅暂停问题分区/**不提交offset、不伪造DLT**，其他分区继续。风险与运维指南明确，该记录仍需要管理员诊断，不自动跳过。
4. 真Kafka隔离：Consumer处理期间暂停MySQL/注入连接超时，恢复后**不重启Control或Listener**、该offset最终消费且每日总数只增加1；无效payload和相同eventId不同payload仍暂停/无ACK，未处理分区不丢事件；低基数诊断区分`transientRetry`与`poisonPause`（当前指标重启会归零，不装作持久度量）。

## F3｜P1-C03：Ledger缺失Outbox时对账不得当成零或空列表

**反例**：`AsyncUsageReadMapper.Sql.reconciliation`以`fg_quota_ledger INNER JOIN fg_outbox_event`作为expected。独占测试库中删除一条Outbox但保留Ledger后，实际授权事实就从对账数据源消失；若Daily也没有对应行，可能直接返回空，不会报告数据完整性异常。

1. 添加**非破坏性、有界、只读的账本完整性诊断**：Ledger无匹配Outbox、Outbox无匹配Ledger、同一chargeId指向多条Outbox都应可诊断；不能把未知`Outbox.eventTime`的行按Ledger.createdAt擅自归属UTC日期后标MATCH。可以新增`integrityWarnings`摘要或独立只读健康接口与页面提示。正常每日账本按Outbox.eventTime归属仍保留，不篡改历史。
2. 保留C4的REPEATABLE_READ只读事务与最多31天/100条/三元组游标，不把SQL转成无界读原始payload或N+1巨量扫描；对于事件时间缺失导致无法确定日期，明确展示“权威Ledger存在但事件时间缺失，需人工核查”并标`INTEGRITY_GAP`，不把该异常误表述为用户Quota超扣。
3. 隔离真MySQL注入：A) 正常1Ledger+1Outbox未消费→PENDING，消费后MATCH；B) 删除Outbox留Ledger→明确异常，不是空列表/0/MATCH；C) 同charge在Outbox伪造第二条→不把1个Grant加成2个expected。恢复正常数据后可再次MATCH。测试只能改本次隔离Lab，禁止修改任何用户数据库。
4. 同步`/console/`“对账中心”显示这些异常，中文指南解释如何检查与恢复，**不能新增自动修改权威Balance/old Ledger**。

## F4｜完整复测/提交和退出标准

1. 初始明确记录F1/F2/F3新反例的失败预期，不删除已有67JUnit/26组Kafka脚本结果。最小扩展`scripts/v11-c-smoke.py`或单独新`scripts/v11-c-review-smoke.py`，覆盖Kafka容器重建持卷、SENT补投、MySQL短暂故障自动恢复、Ledger孤行/重复Outbox; 然后重新运行：`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`、旧B/C产品Node、`python3 scripts/build-console-guide.py --product --check`、旧`python3 scripts/v11-c-smoke.py`及新的真实故障脚本。失败如实报告/修复再跑，测试用例数/状态/退出码真实记录。
2. 保留`docs/progress/evidence/v11-c-smoke.json`和全部旧C0～C4/B/A证据原样；**仅实际运行新测试后**创建`docs/progress/evidence/v11-c-review-fix-smoke.json`脱敏摘要，写实际SHA/命令/退出码/关键数据库数量/失败历史/真实容器隔离条件，不写Token、请求Header/Body、Kafka凭证/用户数据。
3. 在`docs/progress/v11-c-async.md`新增C-10 ChatGPT审查整改真实进度、更新README、`AGENTS.md`和使用指南/同源离线`guide.html`（按现有`scripts/build-console-guide.py --product`生成）。关键复杂代码中文注释按`// 1.` `// 2.` `// 3.`；不引新Java微服务、Kafka Streams、CDC/Debezium/Seata、Vue/React/npm或不必要中间件。
4. 建议Conventional Commits：
   - `fix(v11-c): persist local kafka logs and replay unconsumed sent events`
   - `fix(v11-c): distinguish transient consumer failures from poisoned records`
   - `fix(v11-c): detect missing outbox in authoritative reconciliation`
   - `test(v11-c): prove broker recreation and transient recovery`
   - `docs(v11-c): record review fixes and updated evidence`
5. **完成后停止**，等待ChatGPT复审和负责人1280px/375px/键盘真实浏览器验收；不开始D长压测/10万级SQL，不改任何旧PR base、不合并main。只有原C所有测试及新增F1～F3通过且证据入库才能说C技术质量收口。

**如果本地Codex仍遇历史Git push403**：保留本地合法提交与脱敏报告、明确完整SHA和错误码，不reset/强推、不宣称已推送。GitHub远端此次可读到完整C实现，用户可通过已连接GitHub审阅；推送失败不能写“远端没有任何C代码”。
