# FlowGate｜本地Codex当前唯一阶段入口：V11-C（2026-11-08）

> 负责人已授权“设计C，并同步完成B小修”。C详细设计与分阶段任务已写入GitHub，但**C的Kafka/Outbox Worker/Consumer/用量统计/对账源码尚未开发，C真实测试也未运行**。B的两项复审小修已实际提交Java/前端/SQL/测试源码，**仍待本地真实JDK17/MySQL8.4/Redis7验证**，这一验证是C0第一个工作任务。
> 目标分支`feat/v11-c-async-usage`，来自`feat/v11-b-quota`完整B实现与复审修复；C独立Draft PR→B，原#1～#8和main不自动合并/改base。

## 必须按顺序阅读

1. `docs/design/v11-c-async-execution.md`：**当前C权威设计**。业务事实、8字段事件合同、两张新表、Kafka最小可选环境、Outbox租约、消费DB幂等、管理API、封账对账、前端和安全故障矩阵。
2. `docs/progress/v11-c-async.md` 第C-08节之后：C0～C6唯一可编码的分步任务、提交与实测门槛。原C-00～07只是历史概要。
3. `CODEX_V11_C_EXEC_PROMPT.md`：直接给本地Codex的操作细节，C0先实际补测B小修再开发C1。
4. `docs/reviews/v11-b-static-review-2026-11-08.md`、`docs/progress/v11-b-quota.md`第B-19节、`sql/v11-b-upgrade-preflight.sql`、实际`QuotaChargeService`、`AppCredentialController`及Outbox/Quota Mapper。历史`CODEX_V11_B_REVIEW_FIX_PROMPT.md`为旧派工，本轮**B已写修复源码不需重新设计，只需C0独立运行并修实测问题**。

## 分阶段门槛

- **C0阻断**：仅用隔离MySQL/Redis真实测试B的存量同月订阅/余额冻结409、计量OFF仍可撤销既有APP Token、Java17/Node/双Gateway B旧回归。新补丁没有真正跑通过前**不能启动C1**；测试失败修源码后重跑，不伪造证据。
- **C1**：只给Control增Boot4兼容的`spring-boot-starter-kafka`、`mq`可选单KRaft本地Broker、Async默认OFF和两张ConsumedEvent/UsageDaily新表。**默认无Kafka仍可启动V1/B**。
- **C2**：MySQL`SKIP LOCKED`短事务领取Outbox与lease，COMMIT后KafkaTemplate发送，受控inflight/backoff/FAILED/迟到Owner CAS；允许至少一次重复投递。
- **C3**：收到事件与本地Outbox/Ledger校验后，ConsumedEvent唯一去重+UsageDaily UPSERT同MySQL事务，**数据库真正提交后才确认Kafka offset**；重复消息不双计，DLT不确认时保留偏移。
- **C4/C5**：真实运营API、ADMIN-only失败事件重投、封账UTC日对账与`/console/`“用量统计/异步事件/对账中心”三组业务页面，中文指南同步；SENT不等于已聚合，PENDING不等于错账。
- **C6**：Java17/Node/隔离Kafka+MySQL8.4/Redis7/双Gateway真HTTP和宕机恢复测试，脱敏证据、SHA/退出码、负责人真人浏览器验收待办。完成C后停止，不自行进入D、合并main或做长压测。

## 固定工程约束

Java17、Spring Boot4、Control MVC/MyBatis/MySQL，Gateway WebFlux/Redis/Lua，前端原生ES modules；**Kafka不能放到Gateway或MySQL扣额热路径**。不得引Kafka Streams/Debezium/Nacos/Seata/Redis Stream/新Java微服务/新JS框架。Java/JS关键长逻辑使用充足中文注释，按`// 1.`、`// 2.`、`// 3.`分点。所有程序和证据不能泄露Token、内部密钥、HTTP Body、用户原始日志；保护用户未提交工作树、IDE、.env、已有Docker持久卷。禁止`git reset --hard`/`git clean -fd`/`docker prune`/强推或未经授权合并PR。

**当前真实状态：B小修已编码待实测；C只设计/派工，尚无C业务实现和C测试证据。**
