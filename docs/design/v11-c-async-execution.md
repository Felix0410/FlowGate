# FlowGate V11-C｜Kafka Outbox可靠异步用量与账本对账：详细设计与实现契约

> **设计日期**：2026-11-08。**状态：C设计已完成，C业务代码/测试尚未开始**。
> **基线**：`feat/v11-c-async-usage`基于`feat/v11-b-quota`的实际源码，包含B0～B6和两项2026-11-08复审小修。小修已提交代码、JUnit/SQL/前端与待运行的真实HTTP用例，**未由ChatGPT运行Java/MySQL**，在C0实测门槛确认前不得宣称B修复已验收。
> **本设计的原则**：不改变B计量授权的MySQL事务；只让已有`fg_outbox_event`可靠交付、去重汇总、对账和面向人的状态诊断。新的异步依赖**默认OFF**，不要求V1或B默认运行Kafka。C只修改`flowgate-control`和正式`/console/`、SQL/compose/测试脚本，**不改Gateway数据面、不引入第二个Java微服务、微服务注册中心或新前端框架**。
> **代码落地前沿用**：`docs/progress/v11-c-async.md`的C-08～C-15、`CODEX_V11_C_EXEC_PROMPT.md`；按C0→C6严格顺序提交。

## 0. 业务价值和一条可讲清楚的完整链路

客户A通过应用Token调用受保护计量API。V11-B在**一个MySQL事务**把额度减少1、写`fg_quota_ledger`、写`fg_outbox_event(NEW)`，成功提交才放行。V11-C在授权路径**之外**完成：

```text
Gateway -- 内部Quota HTTP --> Control MySQL Balance + Ledger + Outbox NEW (B已有)
                                         |
                                         | C2 定时领取(短MySQL事务)
                                         v
                                 Outbox LEASED
                                         | KafkaTemplate.send (不持DB锁)
                                         v
                              Kafka topic flowgate.usage.v1
                                         |
                                         | C3 Listener / DB事务
                                         v
                         fg_consumed_event唯一去重 + fg_usage_daily增量汇总
                                         |
                                         | DB提交之后才确认offset
                                         v
                             /console/ 用量趋势 / 事件监控 / 对账
```

必须把三类数据在API/前端/简历表达中严格区分：
- `fg_quota_balance`和`fg_quota_ledger`：**权威**剩余与授权消耗记录。B事务正确性不依赖Kafka。
- `fg_outbox_event`：**待投递/正在投递/已收到Kafka确认/投递失败**，其`SENT`仅表示Broker确认，**不是消费者已经聚合**。
- `fg_usage_daily`：**最终一致的用量读模型**。Kafka抖动可延迟；不得被用于实时扣额度、宣称收费金额或业务2xx成功次数。

**默认失效行为**：Kafka宕机，已经提交的授权继续成功、Outbox保留且积压；若MySQL本身或Outbox插入失败，B整个Quota授权事务仍必须回滚并返回503，不能“先扣后忘记事件”。

## 1. 只读基线冻结和C0的B修复门槛

C0执行之前先固定PR #8最新SHA、所有历史B6证据，然后阅读：
1. `sql/flowgate-v11.sql`实际`fg_outbox_event`（字段`event_id,payload_json,status,next_attempt_at,attempt_count,lease_owner,lease_until,last_error_code,created_at`齐全），`QuotaChargeService.grant()`在同事务写Ledger+Outbox，`QuotaLedgerEntry`、`QuotaBalanceEntry`和`AppCredentialController`。
2. B小修：`QuotaChargeService`对本月历史多订阅和余额冻结字段失败关闭；`AppCredentialController`可在activation OFF撤销并在产品页暴露真实状态。追加的`V11QuotaFrozenBalanceTest`、`V11QuotaCredentialControllerTest`、`scripts/v11-b-console-test.mjs`、`sql/v11-b-upgrade-preflight.sql`和`scripts/v11-b-smoke.py`新增定向场景。
3. **C0阻断**：先在本地隔离MySQL8.4/Redis7真实运行受影响Java17/Node/B6短回归；从只读预检得到0异常的模拟B库，并显式构造A旧数据（同月两个历史非重叠订阅）和余额冻结字段不匹配，证实新授权拒绝且Ledger/Outbox不增加；activation OFF的已签发凭证能撤销，重新启用后旧代次不复活。若不能运行，记录“未验证”并**停止C1**；不改用户真实卷。
4. 不覆盖旧B的`docs/progress/evidence/v11-b-*.json`或155条A历史证据。C0生成`docs/progress/evidence/v11-c0-b-fix-smoke.json`（仅实际运行后提交脱敏摘要）。现有Kafka不是旧B依赖。

## 2. 技术选择、开关与运行环境（C1）

- Java17 + Spring Boot4：只给`flowgate-control/pom.xml`新增`org.springframework.boot:spring-boot-starter-kafka`，**不手工锁与Boot BOM冲突的Kafka客户端版本**。如当前构建仓库/离线依赖不支持，先记录失败，不能用未核实的Kafka版本替代。Gateway、contract、demo无Kafka依赖。
- 复用`compose.local.yml`，通过**profile `mq`**可选启动1个本地KRaft Kafka Broker；已有MySQL8.4/Redis7服务及卷原样不动。KRaft单Broker为开发演示，副本因子1，不宣称生产高可用、跨机耐久性。
- 新开关`FLOWGATE_ASYNC_ENABLED=false`（默认）与`FLOWGATE_KAFKA_BOOTSTRAP_SERVERS`、`FLOWGATE_USAGE_TOPIC=flowgate.usage.v1`。无Broker+OFF时Control必须正常启动，既有B授权继续写Outbox NEW、无Kafka连接/容器依赖。使用`@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")`控制Kafka Consumer、Worker、topic初始化及容器启动，避免默认的Spring Kafka自动初始化去连接未配置Broker。
- 显式启用时Kafka启动失败**不得让已存在的B授权在MySQL有效时失败**；Async子系统返回`BROKER_UNAVAILABLE`，可恢复后继续领取。业务Quota Service不注入KafkaTemplate/不等待Kafka ack。是否允许Control进程保持UP取决于实际listener初始化行为，必须通过Kafka断连真实HTTP测试证明。
- 单Broker显式新Topic`flowgate.usage.v1`，演示1～3分区、1副本；消息key=`applicationId`的十进制字符串，value=严格版本化JSON。消费group`flowgate-usage-aggregate-v1`，两个Control实例共享一个group以避免双消费者无界重复工作；不同group可重复处理但DB仍幂等。
- Spring Boot4 Kafka自动配置、`spring.kafka.*`属性与KafkaListener严格按当前实际依赖API使用，不加入定时任务中重复new producer，避免不受控线程。参考：Spring Kafka/Boot4官方文档（见本文第13节）。

## 3. 事件协议冻结与日统计时间归属（C1）

### 3.1 唯一可消费事件形态

B已存在`fg_outbox_event.payload_json`，C不改写历史Outbox，也不伪造补发：

```json
{
  "version": 1,
  "eventId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "chargeId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "applicationId": 123,
  "apiId": 456,
  "periodUtc": "202611",
  "units": 1,
  "eventTime": "2026-11-08T09:15:00.123Z"
}
```

必须验证：字段恰好8个、version=1、UUID规范、app/api正数、units为1..100、period=YYYYMM、eventTime是UTC Instant且年月与period一致、JSON长度上限4KB、不得有Authorization/Token/Header/Body/IP/用户名。超过版本/结构不合法**不可写UsageDaily**。

### 3.2 特别容易错的日边界

B的`QuotaChargeService`把**授权时数据库UTC时钟`now`**写到`Outbox.payload_json.eventTime`，而`fg_quota_ledger.created_at`由MySQL插入默认时间生成，极端跨UTC日/月的事务内可能相差毫秒乃至不同日期。因此C首版**按Outbox中不可变的`eventTime`确定`dayUtc`**，不能简单按`DATE(ledger.created_at)`分组然后声称与异步日表完全一致。

可信性：消费者在同一MySQL事务读取本地`fg_outbox_event`记录，并按`eventId`与完整标准字段对照收到的Kafka事件；再按`chargeId`取`fg_quota_ledger`，核对appId/apiId/units/period与B账本一致，确认是真正授权的事件。遇不匹配不能聚合/不能当正常重复。**真实运营对账**同样按`Outbox.eventTime`的UTC日期分组关联Ledger，而不是单独按Ledger.created_at取日。
- 单日`2026-11-08`边界用`[00:00Z,次日00:00Z)`；跨月重复chargeId只由B返回历史Grant，**没有新的Outbox**，不应算新月授权。
- B的`eventTime`是`Instant.toString()`，可能毫秒或秒精度；C解析接受合法UTC Instant，无需强迫三位小数，不接受本地时区字符串。

## 4. MySQL增量DDL与模型（C1）

**新增**`sql/flowgate-v11-c.sql`，不修改历史B增量SQL或V1表结构：

```sql
-- 所有连接保持UTC会话。CREATE IF NOT EXISTS，非破坏性重复执行。
CREATE TABLE IF NOT EXISTS fg_consumed_event (
  event_id CHAR(36) PRIMARY KEY,
  charge_id CHAR(36) NOT NULL UNIQUE,
  payload_sha256 CHAR(64) NOT NULL,
  consumed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_consumed_at (consumed_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fg_usage_daily (
  day_utc DATE NOT NULL,
  application_id BIGINT NOT NULL,
  api_id BIGINT NOT NULL,
  authorized_units BIGINT NOT NULL DEFAULT 0,
  grant_count BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(day_utc, application_id, api_id),
  INDEX ix_usage_app_day(application_id,day_utc,api_id),
  CONSTRAINT ck_usage_units CHECK(authorized_units>=0 AND grant_count>=0)
) ENGINE=InnoDB;
```

不额外建`fg_bill`、支付、Kafka通用事件仓库、分布式锁表。Existing Outbox status只有`NEW/LEASED/SENT/FAILED`，`fg_outbox_event`已有leaseOwner/Until/attemptCount/NextAttemptAt，不在C增DDL重建。ConsumedEvent的`charge_id UNIQUE`为额外防线：就算错误人工重发生成新eventId，也不会二次加总。若真的出现同charge不同eventId，应标`CONSUME_DUPLICATE_CHARGE`且保留事件诊断，不产生第二次聚合。

**可选外键**：这两张C新表只保留必要结构与索引，历史业务删除尚未开放，优先避免额外FK导致消费热写冲突；校验通过读取Ledger完成。任何与现有表不一致的SQL需按实际Mapper微调、隔离执行两次验证，不写伪通过。

## 5. Outbox领取、投递与恢复（C2）

### 5.1 严格有界的两阶段流程

在Control进程新增`OutboxClaimService`（MyBatis+TransactionTemplate）、`OutboxPublisher`（Scheduled+KafkaTemplate）与Mapper；**不把数据库事务包住异步Kafka发送**。

1. **短事务领取**：MySQL连接UTC；从`fg_outbox_event`按`id`选最多20条`NEW`到期或`LEASED`但租约已过期的`USAGE_GRANTED`记录，查询使用`FOR UPDATE SKIP LOCKED`。同一短事务写`status=LEASED,lease_owner=<实例UUID>,lease_until=UTC_TIMESTAMP(3)+30s,attempt_count=attempt_count+1`，提交即释放行锁。两Worker并发不能同时持有同一有效lease。重试与领取均只操作Outbox，不访问Balance更新。
2. **事务外投递**：对领取的`id`按appId为Kafka key调用`KafkaTemplate<String,String>.send`，只传标准化8字段JSON；异步结果有10s deadline，每实例最多100个在途send，超上限**暂停领取**而不是无限排队；本地定时调度单实例仅1个claim cycle进行中。
3. Broker明确ACK成功后执行**新短事务**：
   `UPDATE fg_outbox_event SET status='SENT',lease_owner=NULL,lease_until=NULL,last_error_code=NULL WHERE id=? AND status='LEASED' AND lease_owner=? AND lease_until>=UTC_TIMESTAMP(3)`；
   更新0行说明租约被其他Worker抢回/已超时，**不得晚到Worker覆盖新owner**，Broker可能已收到事件，后续重复发送由消费者去重。
4. send失败或超时：同样按`id+lease_owner`条件更新到`NEW`，`next_attempt_at=UTC_TIMESTAMP(3)+backoff(attempt_count)`；上限如8次到`FAILED`，`last_error_code`只存有限枚举，不记异常原始消息/内网地址。退避1,2,4,8,16,32,60s等有限值；超时lease自然重领，无须用Sleep锁SQL。
5. Kafka先ACK但Control在更新SENT前崩溃：lease到期后重发同一个`eventId`，这是**有意允许的至少一次投递**。不要靠`acks=all`宣称跨Kafka/MySQL事务恰好一次。

**操作开关**：`flowgate.async.enabled=false`时Worker/Listener均不运行；已有Outbox NEW积压不清零，恢复Broker再按原eventId发送。Control应用启动无需依赖Kafka可用。

### 5.2 Bounded与安全行为

- `SKIP LOCKED`只适合多Worker队列领取，不能用于余额/账本一致性判断。MySQL官方明确返回的不是完整一致视图。
- Kafka Producer配`acks=all`、`enable.idempotence=true`、业务应用级超时10s、受控重试、关闭自动Topic乱建或显式NewTopic仅ASYNC ON注册。单Broker本机`acks=all`不是跨Broker冗余。
- event payload长度4KB上限、脚本/控制台不显示payload_json原文。记录低基数`status,outcome` metrics与count/gauge，不把eventId/chargeId/appId放Micrometer tag。
- `FAILED`事件为明确待处理，不自动删除；后台只能ADMIN对已知eventId执行**保留原ID**的重试排队（审计）；不能重新生成grant、更不能再扣余额。

## 6. Kafka消费者与MySQL唯一聚合（C3）

Listener条件启用，使用Spring Kafka `@KafkaListener` + String JSON解码，consumer group固定，`enable-auto-commit=false`，`AckMode.MANUAL_IMMEDIATE`、单批次record模式，`Acknowledgment.acknowledge()`**必须在外层`@Transactional`服务调用正常返回（已提交DB）之后**。不能在事务方法内部提前ack。

处理`processEvent`是一个MySQL `@Transactional`方法：
1. 校验事件8字段、版本、UUID/UTC和长度；读取本地`fg_outbox_event`的eventId及其完整不可变payload并逐字段比对，读取`fg_quota_ledger`验证chargeId以及app/api/period/units；**不要相信仅来自Kafka的数字**。找不到Outbox/Ledger时留在可诊断消费失败路径，不假造成功消费。
2. 将标准8字段规范化SHA256存于`fg_consumed_event`。先`INSERT(event_id,charge_id,payload_sha256)`（PK+UNIQUE双保护）；成功才按eventTime的UTC日期`INSERT ... ON DUPLICATE KEY UPDATE authorized_units=authorized_units+VALUES(authorized_units),grant_count=grant_count+1`，同一MySQL事务完成。
3. 已存在相同eventId/chargeId时读取原payload指纹验证一致，一致则只返回`DUPLICATE`并在DB提交后ack；不同内容为`EVENT_CONFLICT`不加总并走人工诊断，**绝不能当作正常重复直接吞掉**。
4. UPSERT/commit失败则整事务回滚，没有`consumed_event`孤立成功；Kafka offset不提前提交，重放后再试。消费者在Control MVC线程池/Listener线程内可使用JDBC，但不能共享到Gateway Reactor事件循环。
5. Broker向消费者至少一次投递，数据库`fg_consumed_event`只保证本Control读模型的**幂等效果**；不宣称跨Kafka/MySQL的exactly-once。

**毒消息与DLT**：建议在同一Kafka本机环境额外topic`flowgate.usage.dlt.v1`。最多5次有限退避；若确认为不可处理的格式/外键不匹配，用`DeadLetterPublishingRecoverer`写DLT且**等Broker确认成功后才允许原offset向前推进**，失败则保留offset/停止对应分区，不能“发DLT失败却ACK”。DLT保留原eventId与来源topic/partition/offset的固定元信息，不记录凭证。C6必须故障注入证明DltBroker不可用时不丢消息。如果当前实际Spring Kafka版本难以保证这条ACK语义，**首版保守退化为暂停问题分区、不提交offset并报警，后台只读诊断，禁止无声跳过**；这比伪实现一个不可靠DLT更好。

## 7. 查询、异步运营和对账（C4）

### 7.1 新管理REST API和角色

沿用`AdminAuthInterceptor`、`V11ManagementFilter`及管理Session，**所有新C写动作仅ADMIN**，OPERATOR/VIEWER只读安全数据，旧V1 OPERATOR路由/发布能力不改变。扩展`v11Path`含`/admin/usage`,`/admin/async-events`,`/admin/reconciliation`，避免不存在的PUT/DELETE绕过ACL。

| API | 用途与返回 | 权限 |
|---|---|---|
| `GET /admin/usage?applicationId=&apiId=&from=YYYY-MM-DD&to=YYYY-MM-DD&afterDay=&limit=20` | `fg_usage_daily`按UTC日期/业务名称展示，默认最近7天、跨度最多31天，limit1..100，unknown不显示0 | ADMIN/OPERATOR/VIEWER |
| `GET /admin/async-events?status=&afterId=0&limit=20` | `NEW/LEASED/SENT/FAILED`只读列表，id、eventId短标、类型、尝试次数、排队/lease时间、lastErrorCode；**不返回payload/token/堆栈** | ADMIN/OPERATOR/VIEWER |
| `GET /admin/async-events/summary` | 总NEW/LEASED/FAILED/待消费数、最旧事件年龄、Async启用与Broker状态（实际探测） | ADMIN/OPERATOR/VIEWER |
| `POST /admin/async-events/{id}/retry` | 只允许FAILED→NEW，retain原eventId、条件where防抢占，操作写审计；不改变Ledger/余额/已消费标记 | ADMIN |
| `GET /admin/reconciliation?from=&to=&applicationId=&apiId=&limit=20` | 仅关闭UTC日，显示权威授权单位、已消费事件单位、Daily聚合值、缺失数量、状态`PENDING/MATCH/MISMATCH`和asOfUtc | ADMIN/OPERATOR/VIEWER |
| `POST /admin/reconciliation/recheck` | 可选仅触发有限已关闭日重新核对与补投**已有**Outbox，不能直接改余额或创建新的grant；需审计 | ADMIN（C首版可不开放，页面只提供刷新） |

- 运营接口错误体沿用requestId、400非法日期/限制、401未登录、403越权、404未找到、409租约/状态冲突、503 DB/Kafka不可用。
- `GET /admin/usage`读模型允许延迟，必须同时展示lastAggregatedAt/asOfUtc/lag状态；Kafka停机/异步OFF时前端标“统计可能延迟”，不能把一条0汇总写成“从未使用”。

### 7.2 精确对账，不把队列延迟误报超扣

1. 只允许`dayUtc <= 昨天(UTC)`，或明确固定历史cutoff；对当天返回400并提示实时数据未封账。查询最多31天。单次对账在MySQL `REPEATABLE_READ,readOnly`快照中执行，同事务读Ledger/Outbox/ConsumedEvent/UsageDaily，避免消费者并发提交时多个查询读到不同版本。
2. 账本权威来源是`fg_quota_ledger`，但**日归属以同一授权的Outbox.eventTime UTC日**为准（注意第3.2节），并核对eventId→chargeId→Ledger唯一关系。处理性能时可以按Outbox.created_at范围加安全边界/索引，但**不可把日分组偷换为Ledger.created_at**。
3. 计算`expectedUnits=SUM(ledger.units)`、`expectedCount=COUNT(ledger.id)`、`consumedUnits/Count`（按实际ConsumedEvent join Ledger）、`usageDailyUnits/Count`。只要有已授权事件没有成功`fg_consumed_event`，标`PENDING`（不宣称错账），同时展示`outboxBacklog`、Kafka/DLT/FAILED风险；**即使Outbox已SENT但Consumer未提交也算待处理**。
4. 已消费件齐全时，若`expectedUnits=usageDailyUnits`且`expectedCount=grantCount`则`MATCH`，否则`MISMATCH`并记录脱敏差异。Kafka lag=0本身不能证明账本对齐，FAILED/LEASED还需检查。
5. 修复：优先手动**重投原eventId**以使幂等消费追平；已消费记录齐全但聚合数值不一致，可在ADMIN确认+审计后在单事务按权威事件重建某个`(dayUtc,app,api)`读模型（C首版建议**仅报告，不提供自动写修复**，避免复杂逻辑无测试覆盖）。绝不改QuotaBalance、Ledger或回退额度。
6. 只在D阶段基于≥10万行真实`EXPLAIN ANALYZE`结果考虑物化日期列、Outbox生成列/索引；C不凭想象添加5个宽索引，也不做全文JSON索引。

## 8. 产品后台导航与用户语言（C5）

正式`/console/`原生ES Modules，不引Vue/React/npm。导航新增“用量统计”“异步事件”“对账中心”，从应用或配额页提供快捷入口：
- **用量统计**：选择“应用名称（code）”、API、UTC日期范围，趋势/表格每日**授权单位、授权次数**，旁边清晰写“异步统计，可能延迟；非成功响应次数/收费金额”，展示最后聚合时间。
- **异步事件**：NEW待投递、LEASED正在发送、SENT已投至Kafka、FAILED需处理，状态数量+最近事件游标、失败原因码及重试操作（ADMIN二次确认）。**SENT不能写成“已统计”；是否已消费另有状态**。
- **对账中心**：显示指定历史UTC日期`expectedUnits / consumedUnits / aggregatedUnits`、差异、缺失和PENDING/MATCH/MISMATCH；PENDING=队列未追平，不写成配额超发。可刷新且有故障空态。
- C OFF或Kafka宕机显示确切不可用状态，并**保留B配额/凭证/发布页面正常工作**。页面所有数据来自真实API，应用选择用名称不要求手填数据库ID；所有列表limit/游标/错误/空态/加载/375px和1280px操作；OWNER真人浏览器验证后才可标体验通过。
- 同步`docs/guides/product-console-guide.md`、`scripts/build-console-guide.py`、`static/console/guide.html`及`README.md`；旧`/test-console/`仍是开发工具不删。

## 9. 每个子阶段要修改的代码文件和提交顺序

| 子阶段 | 精确实现范围 | 交付示例 |
|---|---|---|
| **C0** | B两项小修实测与迁移只读预检、锁定事件字段与当前Git SHA | `test(v11-c): verify b review fixes before async integration` |
| **C1** | `sql/flowgate-v11-c.sql`两表DDL，`flowgate-control/pom.xml`Kafka starter、`compose.local.yml`可选mq profile、`application.yml`默认OFF和Kafka配置；DTO`UsageGrantedEvent` | `feat(v11-c): add optional kafka and versioned usage schema` |
| **C2** | `service/OutboxClaimService.java`、`service/OutboxPublisher.java`、`mapper/OutboxEventEntryMapper.java`新增claim/lease/ack/retry SQL，修`OutboxEventEntry`补nextAttemptAt字段 | `feat(v11-c): publish leased outbox events at least once` |
| **C3** | `service/UsageAggregationService.java`、`listener/UsageEventListener.java`、`mapper/{ConsumedEventMapper,UsageDailyMapper}` + 实体，手动ack/事务/DLT | `feat(v11-c): dedupe usage consumers transactionally` |
| **C4** | `controller/{UsageController,AsyncEventController,ReconciliationController}.java` + read-only核对Service/Mapper；`AdminAuthInterceptor.v11Path`明确ACL，Audit | `feat(v11-c): expose backlog usage and reconciliation APIs` |
| **C5** | `static/console/{app,usage,operations}.js`（必要时新`async-events.js`）、CSS/指南构建器 | `feat(console): manage async usage and reconciliation` |
| **C6** | 新JUnit、Node`scripts/v11-c-console-test.mjs`、隔离`scripts/v11-c-smoke.py`、脱敏证据/阶段记录 | `test(v11-c): prove crash replay and safe recovery` |

**任务粒度**：每个C1/C2/C3/C4/C5至少一个Conventional Commit和对应定向测试，C6只有真实测试通过才能勾选完成。单文件过长按业务拆小文件，不为抽象而建一层层BaseService/通用事件总线。Java/JS复杂长流程中文注释按`// 1.`、`// 2.`、`// 3.`分点。

## 10. 故障注入与C6最终定向验收矩阵

| 真实场景 | 期望事实 |
|---|---|
| Async默认OFF、Kafka未启动 | Control/Gateway/Quota/旧Caller旧V1工作正常，Ledger/Outbox继续写NEW |
| Kafka Broker停机 | 新计量Grant依然200（额度不足429），Outbox继续NEW/LEASED过期可领，异步状态显示延迟 |
| 恢复Kafka | Outbox逐步SENT，ConsumedEvent与UsageDaily追平，无重复增加 |
| Broker确认成功，标记SENT前Kill Control | 同一eventId租约到期重新发送，ConsumedEvent只1条，Daily只加1次 |
| Consumer数据库事务提交后，不确认Kafka offset就杀进程 | 重放时ConsumedEvent已存在，Daily不再加，最后ACK |
| 双Worker同时claim100条/租约到期、晚到worker回写 | 不同时持有有效lease，迟到owner更新0行不覆盖现有owner，最终可重领 |
| 重放同eventId与不同payload；同chargeId换eventId | 一致重复安全跳过；不同内容进入异常诊断/DLT/不加总 |
| Consumer失去DB或DDL不兼容，DLT不可用 | 不丢offset、不确认已统计，Consumer暂停或重试并可诊断 |
| 日跨UTC边界/同chargeId跨月历史 | daily分组按Outbox.eventTime，Ledger/Outbox/ConsumedEvent一致，不把12月重试算成新月 |
| 手动重试FAILED / 两人并发点重试 | 只一人状态迁移成功；原eventId不变、审计成功、无第二次额度扣减 |
| 运营RBAC | ADMIN可重试，OPERATOR/VIEWER可读不可POST，旧V1 ADMIN/OPERATOR权限原样 |
| 关闭UTC日对账，Kafka队列积压 | PENDING带未消费数；追平后MATCH；故意修改隔离库读模型后MISMATCH，不自动调余额 |
| Java/Node/两Gateway与真实Kafka/MySQL/Redis隔离测试 | Java17 Maven受影响模块退出0、真实20+异步场景证据、Node/产品离线指南核对；本人浏览器仍单独验收 |

**C仅定向性能样本**：记录worker吞吐、实际失败/重试/lease、消费延迟与控制面DB负载；不声称Kafka能提高B每次Quota授权P95。D才做10万级SQL/四组公平压测。

## 11. 停止条件、风险与阶段定义

- **停止C1**：C0无法证明B修复后月度账本和紧急撤销不会回归。
- **停止C2**：无法证明Worker不长时间持有MySQL事务等待Kafka；只调用`send()`而不等待真正ACK就标SENT。
- **停止C3**：无法证明`INSERT consumed_event + UPSERT usage_daily`同事务、DB提交之后才ack；发送重复会累计两次。
- **停止C4/C5**：read API暴露Outbox原始payload/凭证，或把统计延迟显示成“0消费/超额”。
- **停止C6**：Kafka挂掉导致B授权被Kafka依赖拖死、DLT写失败但offset已提交、SENT冒充已消费、强行修改用户持久化数据。
- **不自动开始D，不自动合并PR #1～#9/main**。C代码做完等待ChatGPT源码复审+负责人真人前端体验；D的最终长压测、稳定性和真正主线收口需单独授权。
- 本地`source .env`可能包含敏感值；程序及证据只写脱敏摘要，**严禁将Token、内部密钥、Kafka认证密码、用户数据/日志上传Git**。
- Kafka本地1节点不属于生产HA，C交付在简历上应表述为“可复现的至少一次投递与数据库幂等消费”，**不能表述为跨Kafka和MySQL的分布式事务严格exactly-once**。

## 12. V11-C对项目的实质贡献

完成后FlowGate将形成完整业务闭环：**客户应用/套餐 → Gateway可信准入 → MySQL权威授权流水+Outbox → Kafka异步用量事件 → 去重日汇总 → 运营对账与失败重放**。这比只展示“用了Kafka”更有说服力，因为能解释宕机、重复、延迟、积压和与权威余额不一致时该如何处理。业务形态依旧是中小型OpenAPI用量治理平台，不伪装成账务结算系统。

## 13. 参考API（实施时核对当前实际Boot版本）

- [Spring Boot 4 Kafka支持](https://docs.spring.io/spring-boot/4.0/reference/messaging/kafka.html)：KafkaTemplate/KafkaListener、spring.kafka外部配置。
- [Spring Kafka Quick Tour](https://docs.spring.io/spring-kafka/reference/quick-tour.html)：Boot starter-kafka由BOM管理兼容版本。
- [Spring Kafka Listener Container](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)：手动ACK与offset管理。
- [MySQL 8.4 Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)：SKIP LOCKED只能用于队列样式工作领取，不用于权威余额一致性。
