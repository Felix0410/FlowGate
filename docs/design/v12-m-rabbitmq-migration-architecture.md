# FlowGate V12-M｜统一RabbitMQ、退役Kafka完整技术设计（2026-11-19）

> **阶段：V12-M详细架构定稿，仅设计/未编码/未执行迁移。** 新设计分支`docs/v12-m-rabbitmq-convergence`来自真实B功能HEAD `feat/v12-b-orders@c857d3b8532ae98dcfb208d10dd140efa0a3037b`，不覆盖PR#17或用户本地未提交文件。V12-B PR#17仍Draft，最终复审/负责人手测及证据缺口须在M0核验。设计事实审计见[源码盘点](../reviews/v12-m-preflight-audit-2026-11-19.md)。负责人新的ADR-14优先于历史“V11 Kafka永久保留”条款；历史资料可保留，但最终运行架构只RabbitMQ。

## 1. 唯一业务目标与边界

**单消息中间件**：在当前可部署版本中，Kafka Broker、Spring Kafka/JAR、Listener/Producer/Admin、配置环境变量、Compose服务及自动测试依赖全部退役；FlowGate既有V11 APP级授权用量、V12-A APP×API月用量、V12-B Commerce订单权益，和后续V12-C商品抢购，均只用RabbitMQ。

**不变的权威事实**：`QuotaChargeService`原MySQL余额/额度Ledger/Outbox一个事务；`fg_quota_ledger`、`fg_outbox_event`、`fg_consumed_event`、`fg_usage_daily`原记录和键、事件8字段/版本/UUID/UTC日归属；V12独立API Ledger/Outbox/Daily及Commerce订单/Grant全不重做。Rabbit只是至少一次传输；消费者既不能向Gateway发授权，也不准直接相信消息中的消费单位。对历史数据**不删除、不重新累计、不归零，不把Kafka offset当权威**。

**非目标**：不开发C秒杀、优惠券/真支付、D正式压测，不转微服务、不上CDC/Seata/Redisson/跨系统事务、不建设Kafka→Rabbit双写常驻系统，不迁移所有历史Kafka消息物理字节（只保障MySQL有权威来源的业务事件恢复），不自动删除原用户Kafka持久卷和容器。

## 2. 三条Rabbit业务链的最终隔离

| 流 | MySQL权威事实与幂等 | Rabbit（同Broker独立拓扑） | 必须保持 |
|---|---|---|---|
| **LEGACY_USAGE V11** | `fg_quota_ledger` + `fg_outbox_event` → `fg_consumed_event`(eventId PK、chargeId UNIQUE) + `fg_usage_daily` | Direct exchange建议`flowgate.usage.legacy`；`.aggregate`主quorum、`.retry`5s TTL quorum、`.poison`quorum | 旧严格USAGE_GRANTED八字段与version1；原eventId/chargeId+eventTime UTC；消费`UsageAggregationService.process` |
| **V12_API_USAGE** | `fg_api_quota_ledger` + `fg_api_usage_outbox` → `fg_api_consumed_event` + `fg_api_usage_daily` | 原`flowgate.usage.v12`三quorum队列 | 原样，不把旧消息送进新事件Schema |
| **V12_COMMERCE** | `fg_business_outbox` → `fg_business_event_consumed`及Order/Grant/Slot | 原`flowgate.commerce.v12`三quorum队列 | 原样，模拟支付事务先提交，消费开权益后ACK |

建议`LegacyUsageRabbitConfiguration`、`LegacyUsageRabbitPublisher`、`LegacyUsageRabbitListener`独立于A的`ApiUsageWorker`和B的`CommerceWorker`；可复用现有`messaging/RabbitDelivery`中已证明的publisher confirm、mandatory return、retry头/poison逻辑，但用独立Bean/路由和Consumer身份避免误注入。Broker真实版本以项目隔离演练为准（当前B0记录3.13.7），单Broker持卷重启**不是多节点HA**。Rabbit quorum at-least-once DLX需实际检查`x-overflow=reject-publish`、`x-dead-letter-strategy=at-least-once`与适用feature flag，不能靠Java声明推断已落盘。官方参考：https://www.rabbitmq.com/docs/3.13/quorum-queues 、https://www.rabbitmq.com/docs/3.13/confirms 。

## 3. 为安全跨Broker切换新增最小持久栅栏

**必须有DB级模式与代次，不可以只用两个环境布尔开关**。新隔离增量文件拟`sql/flowgate-v12-m.sql`，仅新增一个小型运维控制表`fg_legacy_mq_cutover`，不更改旧V11账本：

```sql
CREATE TABLE IF NOT EXISTS fg_legacy_mq_cutover (
 id TINYINT PRIMARY KEY,
 mode VARCHAR(16) NOT NULL,
 epoch BIGINT NOT NULL,
 cutover_max_outbox_id BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 changed_by BIGINT NULL,
 CHECK (id=1),
 CHECK (mode IN ('KAFKA','FROZEN','RABBIT')),
 CHECK (epoch>0),
 CHECK (cutover_max_outbox_id>=0)
) ENGINE=InnoDB;
-- 首次插入KAFKA/epoch=1须显式单次迁移，不能用自动启动器改已部署环境
```

- **兼容过渡版本**所有旧用量Publisher必须持DB栅栏检查并在`KAFKA`才允许新claim/发送；新的Rabbit发布者与监听器只有`RABBIT`才能运行或处理消息；`FROZEN`两边都禁止新增投递，但Quota授权事务继续正常插入`NEW` Outbox。
- 切换操作必须ADMIN显式执行、MySQL行CAS`WHERE epoch=? AND mode=?`并写现有审计表；只允许`KAFKA→FROZEN→RABBIT`。`RABBIT→FROZEN→KAFKA`仅供M2过渡版的**受控回退**且需先停Rabbit消费者/发布者、处理待投递并重新审计；最终移除Kafka客户端后的发布物**不提供回退到Kafka功能**。可保留迁移历史模式枚举字符串用于审计，不代表运行Kafka依赖。
- **防混合旧二进制**：B/旧V11代码根本不读取新控制表，DB栅栏无法拦住旧Kafka版本。因此升级时必须先完成**所有Control实例清单、旧实例退出/不可接流**，再由兼容过渡版本全员接管；无法证明全实例升级和旧Publisher全部停止时必须中断切换，不可宣称“有锁就安全”。更稳妥的首版切换在**显式维护窗口**暂停所有Control的旧异步Worker并重启受控Control集合；不承诺零管理面停机。
- 并发实际竞态：即使模式检查为KAFKA，可能在发送过程中变FROZEN。必须在变更前先禁止接入新旧Publisher的*新领取*、等待`inflight=0`、所有`LEASED`租约/异步回调有确定结果或已过期并持久记录，且停旧Kafka消费；再记录切换高水位。不能只在定时器开始时读取mode就切换。若无法实现原子阻断/耗尽旧回调，直接停止旧Control实例，等租约到期后再切。
- 不建议无约束地通过Rabbit发布又让Kafka继续消费；即便幂等可避免重复日报，也会让交付证据和后续删除顺序不可审计。

## 4. 从旧Kafka到Rabbit的分阶段切换（可恢复、拒绝伪成功）

```text
旧V11 MySQL: Balance → Ledger + Outbox[NEW/LEASED/SENT/FAILED]
                                |
                 KAFKA(仅旧兼容模式)
                     |
      M0盘点/健康扫描、旧二进制退出
                     |
             M2 FREEZE + Kafka发布者彻底停写
                     |                \
                旧消费者排空         允许新Gateway授权继续写NEW
                     |
            old inflight=0、租约已清
                     |
         DB RR完整性检查 + 高水位记录
                     |
        ADMIN CAS：FROZEN(epoch n) → RABBIT(epoch n+1)
                     |
       Rabbit legacy用量消费者启动，领取NEW
                     |
       受控恢复未消费的SENT/FAILED旧事件
                     |
       fg_consumed_event UNIQUE(eventId,chargeId)
                     |
    fg_usage_daily同事务累计，COMMIT后Rabbit ACK
                     |
       对账追平 + Rabbit故障重演通过
                     |
       移除Kafka依赖/服务/运行脚本 → Kafka-free包
```

**旧SENT未消费修复是本阶段难点**：
1. `SENT`只表示**旧Broker曾确认**，不表示写入`fg_consumed_event`；旧消费者毒分区暂停或Broker永久故障可能留悬空。切前存储可审计的`cutover_max_outbox_id`及只读快照：按Outbox `id`批量扫描`USAGE_GRANTED`，以`NOT EXISTS fg_consumed_event(event_id)`识别缺口；每笔严格`UsageGrantedEvent`解析并核对`fg_quota_ledger(charge_id)`，异常单独列`INTEGRITY_GAP`阻断推进，不自行更改Ledger。
2. `NEW`自然让Rabbit按原eventId领取；失效`LEASED`先等待租约过期，由旧claim逻辑重领；`FAILED`需ADMIN或有审计的迁移批次逐条确认原payload合法、未消费再恢复到`NEW`（原attempt_count根据明确恢复原因安全重置）。`SENT`历史积压超过冷却窗口（至少300秒 + 实例启动冷却）、未消费且旧异步已停止，才由*受控、限量、记审计的迁移replay*改回NEW；消费已发生的绝不强制二次发额。
3. 批次大小建议<=100，以Outbox id游标可断点恢复；目标事务CAS限定eventId、当前status、旧lease owner为空/过期、`NOT EXISTS consumed`，每次恢复必须记录同一原eventId、原chargeId、原payload fingerprint、触发人、旧状态与恢复原因；不得调用QuotaChargeService重新扣额度或生成新事件UUID。
4. 如果旧Kafka无法恢复/无法排空，可在明确记录Broker/分区/offset未知后的**权威MySQL恢复模式**继续；不需要保留运行Kafka，但必须列出Outbox/Consumed缺口、扫描覆盖范围、旧消费者全部停止事实、可安全重放原eventId的证据。不能仅因为Rabbit队列为空就宣布历史已追平。
5. 对新授权产生的Outbox，新Rabbit Publisher只能在RABBIT栅栏且Broker真实Ready时claim；Broker故障不影响MySQL授权写Ledger/Outbox，但日汇总暂时PENDING。不可将Broker不可用转成Gateway扣额失败或重复返额。
6. 若源Outbox或Ledger本就存在孤儿/重复`chargeId`/无效UTC时间或事件指纹冲突，使用现有V11`LedgerIntegrityMapper`诊断，报告明确的INTEGRITY_GAP并停止自动追平；不能为了“数据一致”直接删除原历史事实。

## 5. Rabbit Legacy发布/消费合同

- **发布**：现有`fg_outbox_event`事务已完成后，由独立Rabbit Publisher调用原`OutboxClaimService`短事务领取（原30s租约/8次尝试上限按真实代码保留）；解析原严格8字段并核对行eventId，再以原`event_id`为Rabbit messageId，持久消息publish。**publisher confirm ACK且mandatory无return**才通过CAS写SENT；NACK/Return/超时/连接异常保持可恢复的NEW或FAILED并按原attempt上限；如果confirm后回写SQL失败，重投仍以原ID去重。对过期租约只能原owner CAS，迟到回执不能污染新owner。
- **消费**：严格旧V11 `UsageGrantedEvent`，先在边界包装Jackson3异常为`InvalidUsageEvent`/确定性永久失败；调用旧`UsageAggregationService.process(payload)`，其Outbox+Ledger核对、按eventId和chargeId唯一去重与fg_usage_daily UPSERT必须真实MySQL事务提交才`basicAck(deliveryTag,false)`。禁止把`flowgate.usage.v12`的APP/API新payload喂给旧V11 Listener。
- **失败**：格式坏/无源Outbox/权威冲突→`basicReject(requeue=false)`到独立Poison quorum且记录固定错误码；确定性MySQL暂故障→在Retry队列收到独立publisher confirm且无return后ACK原件，5秒TTL转主队列；未知Runtime按有界（如最多12次）持久重试再Poison，不能5秒无限回炉或永久无限热重启。原eventId不变，retry count带校验头/可结合Rabbit x-death；Poison不应自动重注入主队列。
- **运营**：旧`/admin/usage`、`/admin/reconciliation`、`/admin/async-events`、`/admin/async-events/summary`、`/admin/async-events/{id}/retry|replay`路径及原ADMIN权限保持。旧Kafka Topic/Group/pausedPartition呈现改为Rabbit真实Exchange/Queue/Consumer状态、quorum/Retry/Poison计数与采样时间、`CUTOVER_FROZEN`和pending backlog；不能用“队列为空”替代MySQL完整性对账。**危险补投只能ADMIN审计CAS、保留原eventId，300秒等待期/进程启动冷却不能丢**。
- **统一API用量读模型**仍严格区分V11旧APP月池和V12 APP_API新池；Console可统一导航，但数据源必须显示V11_APP、V12_APP_API来源，普通用户额度调用不能混桶。

## 6. 最终项目清理范围与无Kafka验收定义

**Java要修/删除**：
- 替代`async/AsyncConfiguration.java`的NewTopic生成、`async/UsageKafkaConfiguration.java`、`async/UsageTopicRecovery.java`、`async/UsageConsumerErrorHandler.java`及`listener/UsageEventListener.java`；
- 替代`service/OutboxPublisher.java`中的`KafkaTemplate`和旧`async/AsyncBrokerProbe.java`中的`KafkaAdmin`，改写`service/AsyncOperationsService.consumerStatus()`对`KafkaListenerEndpointRegistry`及paused partitions的检查；原`UsageAggregationService`/旧MySQL Mapper/QuotaChargeService不要重写；
- 删除`flowgate-control/pom.xml`的`spring-boot-starter-kafka`及实际引入的全部Kafka传递依赖，去掉`application.yml`的`spring.kafka`、`FLOWGATE_KAFKA_*`、`FLOWGATE_USAGE_TOPIC/GROUP`和Topic/offset配置；`flowgate.async.enabled`可以保留**通用旧用量统计开关**但含义改为Rabbit，不能通过这个通用开关偷偷建立Kafka Bean；
- 更新`compose.local.yml`、`compose.v11-demo.yml`、`compose.v12-a.yml`、`.env.example`、脚本`v11_local_runtime.py/v11_local_core.py`、`v11-local.py`、B0/B7专用脚本和测试编译classpath，使当前`doctor`和隔离全链路只检查Rabbit；旧`V11Kafka*.java` CLI探针和Kafka专用JUnit要替换为Rabbit测试并从**现行可执行构建路径**淘汰，可通过Git历史查看原测试，不篡改已有旧通过证据。
- 更新Console异步诊断页、运维/本地启动/发布指南与README。历史目录里注明“V11阶段曾使用Kafka，现已退役”；可以保留历史叙事词Kafka，**但当前源码和运行依赖不能再启动或导入Kafka**。
- 不允许用删除现有用户`kafka-data`卷或`docker down -v`来满足“无Kafka”；移除Compose中的服务/volumes定义只改变未来实例的依赖，用户已有数据卷原样留存等待单独授权处置。

**最终门槛**：在全新隔离项目中`docker compose config`仅显示MySQL、Redis、Rabbit（加Java服务如有），`mvn dependency:tree`无Kafka客户端/Starter，代码现行运行路径不存在Kafka Producer/Consumer/Admin/Topic/offset依赖，**完全不启动Kafka容器**依然跑V11旧授权用量统计/昨日MATCH、V12-A API用量、V12-B Commerce实际订单→Rabbit唯一权益→Gateway调用/退款与运营页；真实Rabbit停机/持卷/Poison/TTL/确认故障都需通过。用户根.env/常驻服务/旧卷保持未操作，PR维持Draft并等负责人手测。

## 7. 迁移安全止损与回滚

- **M1兼容版本前**：检查B完成度和跨分支文档/代码一致性，建立脱敏数据库计数备份、消息/队列配置信息、旧Kafka消费分区及lag的只读事实，切换清单按实例/owner签名。M0不通过不得迁移。
- **切换前失败**：保持`mode=KAFKA`和全部旧实例、Outbox不变；修新Rabbit拓扑后重试。此时不得删除Kafka代码/容器。
- **FROZEN时失败**：不重置权威账本，可以继续保持旧用量异步暂停（Gateway仍可扣额并写Outbox）；若确认旧Kafka兼容Worker已全部停止且新Worker尚未启动，可以通过审计CAS安全回到KAFKA，仅过渡版本允许。
- **已切RABBIT但未删Kafka时失败**：优先保持FROZEN，修Rabbit+从MySQL按原事件补投；如确需退回Kafka，必须先停全部Rabbit发布/Consumer并确认inflight/租约，重新按Consumed检查缺口后在兼容版本审计回滚；不能随手改环境变量导致双链并存。可能需要运营窗口与停服务，不承诺无损秒级热切。
- **最终Kafka-free包发布后**：回滚目标是上个**仅Rabbit且经验证**的包；要回退到Kafka仅有单独新变更/明确人工授权，绝不静默拉起旧Kafka。安全的默认做法是停止异步Worker而非回改MySQL。
- **双Control**：过渡切换和终态，都要测试一台旧新版本交叉启动的阻断机制；只容许全部同协议且同代次的实例接流。任何未知旧实例存在就中止切换。

## 8. 与下阶段的硬衔接

**阶段顺序**：V12-A（已有代码）→V12-B（已提交候选，待最终复审）→**V12-M（此设计，尚未实现）**→V12-C（Rabbit抢购）→V12-D（无Kafka验收/压测）。

V12-M分段执行和逐段证据见[详细任务书](../progress/v12-m-rabbitmq-convergence.md)，给本地Codex的唯一交接提示是根目录`CODEX_V12_M_EXEC_PROMPT.md`。**必须在M完成真实Kafka-free联调后才能开始C；仅靠删除pom或改Compose不算迁移成功。**
