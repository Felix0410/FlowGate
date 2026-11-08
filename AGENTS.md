# FlowGate｜当前本地Codex唯一任务：V11-C源码复审定向修复（2026-11-08）

> **V11-C的C0～C6 Java/Kafka/SQL/前端与实际测试证据已经真实提交GitHub**，PR #9仍Draft。Codex记录JDK17 **67 JUnit**、**26组真实Kafka/HTTP故障场景**及109 Ledger=Outbox=Consumed=每日授权数，ChatGPT已审读远端源码与证据但**未独立运行本机Java/Kafka/MySQL或真人浏览器**。目前不是从头开发C，也不是开始D。
>
> **当前唯一执行合同：`CODEX_V11_C_REVIEW_FIX_PROMPT.md`**；源码审核报告`docs/reviews/v11-c-static-review-2026-11-08.md`；真实历史实施`docs/progress/v11-c-async.md`的C-09/C-10。旧`CODEX_V11_C_EXEC_PROMPT.md`为已完成C0～C6历史阶段合同，不再按原路线从头重写。

## 本轮必须依次完成的三个边界

1. **P1-C01：Kafka容器日志持久性 + 已SENT未消费的恢复**。现Compose Kafka日志无volume，容器重建/保留期清除会让已Broker ACK、Outbox=SENT而尚未Consumed的事件失去自动重发路径。新增独立Kafka命名卷，并实现有安全时间门槛、ADMIN审计、CAS的原eventId补投；在隔离Compose/隔离Broker丢失场景真实测不多扣/不双计。
2. **P1-C02：区分MySQL瞬时故障和永久毒消息**。当前`UsageKafkaConfiguration`所有异常都暂停分区。数据库断开又恢复时需自动有界退避、同offset重试、最终追平，不需要重启Control；真正invalid/event冲突仍暂停、不ACK、其他分区继续。
3. **P1-C03：Ledger与Outbox完整性对账**。`AsyncUsageReadMapper.Sql.reconciliation`的INNER JOIN会忽略缺失Outbox的权威Ledger。增加有界只读不完整事件诊断，孤儿Ledger/重复Outbox必须显示完整性异常而不是0/MATCH；真实MySQL反例、中文前端提示。

## 实测、Git、安全

- 目标分支`feat/v11-c-async-usage`，base`feat/v11-b-quota`，PR #8/#9继续Draft。先`git status -sb`、`git fetch origin`、`git log -1`核对已提交工作；**绝不reset-hard/clean-fd/强推/merge main**。
- 必须使用随机端口/新容器及新的Kafka测试卷，禁止操作用户现有MySQL/Redis/Kafka持久卷、.env、IDE/旧JVM。新增Kafka卷是Compose配置变更，不能不经负责人允许重启用户已有Kafka并误当迁移完成。
- Java17 Spring Boot4/MySQL8.4/Redis7/Kafka4.1.1（Control-only Kafka），原生ES modules；**不加Kafka Streams、CDC、Seata、Nacos、额外Java服务、Vue/React/npm**。关键长逻辑中文注释按1/2/3逐项写。
- 按修复合同扩展JUnit、Node旧新产品和两Gateway真实Kafka/MySQL故障测试；**保留原C0～C6 26组与67JUnit的历史证据**，新`docs/progress/evidence/v11-c-review-fix-smoke.json`只有实测后才生成。进度记录完整SHA、命令/退出码、通过失败跳过数以及原C测试回归。
- 完成后停在PR #9 Draft并交ChatGPT复审与负责人真人1280px/375px/键盘测试。**不开始V11-D、不合并main或所有Stacked PR**。

**当前真实状态：C0～C6已实现有历史测试证据，本轮3项静态审查边界待Codex编码与新故障实测，整个V1.1未最终交付。**
