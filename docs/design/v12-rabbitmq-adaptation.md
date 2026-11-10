# FlowGate V1.2｜RabbitMQ适配后的完整消息架构定稿补充（2026-11-10）

> **适用优先级**：负责人在V12-A实施期间明确采用V12 RabbitMQ，已写入`docs/design/v12-architecture-decisions.md#adr-13v12消息链改用rabbitmq2026-11-10负责人明确授权`。本文件将该决定延续到未来V12-B/C；**覆盖此前V12设计中“新订单Kafka Topic、Group、Consumer Offset”的措辞**。旧V11-C Kafka**保留、不得删表迁移或暗改历史**；若负责人以后要求全平台统一Rabbit，须另立迁移及回滚ADR。

## 1. 真实现状与目标

- **已提交代码V12-A**：RabbitMQ exchange `flowgate.usage.v12`、quorum主队列`flowgate.usage.v12.aggregate`、TTL retry与poison；MySQL`fg_api_usage_outbox`／`fg_api_consumed_event`／`fg_api_usage_daily`权威投递与对账，`ApiUsageWorker` Broker confirm+mandatory return、手动ACK。源：`feat/v12-a-portal@472d9eb02a49...`（新补审文档提交在其后）。使用`rabbitmq:3-management`实际3.13.7、单节点、隔离持卷。旧Kafka `flowgate.usage.v1`未动。
- **V12-B未来设计**：同一RabbitMQ Broker上新增**独立commerce交换机/队列、单独Rabbit listener和独立业务Outbox/消费去重**，不与A读模型共用队列，避免订单占用日报重试/死信。不得替换为新增Kafka业务Topic/Redis Stream；将旧设计中Kafka的offset ACK改成Rabbit`basicAck(deliveryTag,false)`。
- **V12-C未来设计**：抢购`PROMO_INTENT_ACCEPTED`也进入B的commerce业务交换机与受控Consumer（可按eventType路由不同队列/独立worker，但使用同一Broker），用同一订单状态机与业务Outbox。禁止Redis Lua赢后直接发Rabbit并返回202；MySQL持久intent+slot+reserved+Outbox同事务提交后才算受理。

## 2. 逻辑链路与事务边界

```text
APP_API Gateway Grant
  -> Control MySQL [Balance - units, Ledger, API Usage Outbox] COMMIT
  -> Rabbit publisher confirms routed persistent message
  -> Usage quorum queue
  -> Consumer MySQL [ConsumedEvent UNIQUE + Daily UPSERT] COMMIT
  -> Rabbit manual ACK
  -> retry TTL queue / poison queue (failure path; no silent discards)

NORMAL_ORDER / PROMO_INTENT
  -> Control MySQL [Order or persistent Intent + entitlement BASE slot + stock reservation + Commerce Outbox] COMMIT
  -> Rabbit Commerce publisher confirms persistent message
  -> Commerce quorum queue
  -> MySQL Worker [order state CAS + unique grant/stock + consumed marker] COMMIT
  -> Rabbit manual ACK
  -> failed dependency -> durable retry / permanent poison -> isolated inspection/ADMIN audit replay
```

- **不依赖RabbitMQ exactly-once**：Confirm/NACK/returned/timeouts只决定Outbox是否可安全推进SENT；进程在Broker确认后、更新SENT前崩溃会重复发布，消费侧靠`event_id`和业务唯一键保证不会重复扣库存、开权益或记日报。
- **交换机/队列应隔离**：建议`flowgate.commerce.v12` DirectExchange、`flowgate.commerce.v12.orders` quorum、`flowgate.commerce.v12.retry` quorum TTL、`flowgate.commerce.v12.poison` quorum；固定routing key仅按已知eventType映射，原消费端不得接收其他产品的未认证事件。按具体类型可再拆多队列，但首版不做额外微服务。
- **Rabbit关键语义**：用`CorrelationData`等publisher confirm并启用mandatory返回；成功ACK必须等到业务MySQL事务提交后；临时依赖错误在投递重试副本获得confirm/无return后再ACK原件；永久JSON/协议/数据库权威冲突应`basicReject(requeue=false)`进入独立持久Poison，而不是在5s重试队列无限回炉。
- **有界重试和告警**：请求/消息类型白名单、事件版本和原eventId不变；对临时故障保留有限重试策略和可查询状态（最后重试失败进入需运营处理的持久隔离），禁止无限热循环；不记录原始支付/Token/个人信息。隔离区受控重投须有ADMIN权限、等待期/必要双重确认与审计。
- **队列持久性不等于Broker多节点HA**：单Broker+本项目新volume只保护普通容器重建，不能宣称Rabbit quorum已有真正多数派可用；RabbitMQ 3.13 at-least-once quorum死信需要`x-dead-letter-strategy=at-least-once`、`x-overflow=reject-publish`、已启用stream_queue feature flag，必须用真实Broker读参数/feature flags并实际测试死信到达；见[官方3.13文档](https://www.rabbitmq.com/docs/3.13/quorum-queues)。

## 3. 订单异步合同映射（不更改商业权威）

| 原设计Kafka词汇 | V12 RabbitMQ合同 | 保留不变的业务不变量 |
|---|---|---|
| 业务Topic | Commerce DirectExchange + quorum main/retry/poison队列 | 订单/库存/权益和业务Outbox都在MySQL |
| Producer ACK | publisher confirm **且**无mandatory return，才能SENT | 多次发送仍原eventId、单实体只一个有效状态 |
| Consumer offset commit | MySQL事务成功才`basicAck(tag,false)` | 付款收据/Grant/Slot/Stock不可重复变动 |
| Seek/Pause与重放 | retry TTL隔离、Poison保留、ADMIN审计补投 | 临时故障自动恢复，永久坏消息不静默丢弃 |
| Consumer group | 多Consumer竞争同一队列 | 按事件aggregateId和数据库行锁保证有序状态CAS |
| Broker故障重启 | durable quorum/持卷，Outbox可重投 | 202只代表MySQL Intent成功，不依赖Broker运行时的即时响应 |

**支付/超时竞态仍以MySQL同一订单状态CAS决胜；保留B/C原设计的BASE_SLOT持有人、MySQL库存`reserved+sold<=total`、IntentTimeoutCloser、无Token泄露、不跨月改权益。** 不要因为换RabbitMQ放松这些约束。

## 4. 未来B/C前置硬线

- 完成[当前A阶段Rabbit静态复审](../reviews/v12-a-rabbitmq-static-review-2026-11-10.md)与其定向修复。不能让新订单重用已发现可能无限回炉的JSON坏消息路径。
- 新商品支付/抢购上线前，在独立Rabbit 3.13与MySQL8.4隔离Compose验证：Broker发送确认失败（Outbox可恢复）、Broker真实重启/消息保留、持久retry→消费/Poison队列、重复/乱序100次、付款vs关闭、Intent超时释放、Grant唯一/不双开。失败历史和数据口径必须可追溯。
- B/C任务书将`com.flowgate.control.commerce`服务和`fg_business_outbox`独立于A异步读模型，不把两套Consumer混在一个RabbitListener或复用旧V11 C Kafka八字段事件代码。启动开关建议`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED=false`默认安全OFF；启用只影响隔离演示，真实部署需另经负责人授权。
- **此文件只定架构/合同，本轮没有创建任何B/C Java/SQL/Rabbit队列源码，也没有迁移旧V11 Kafka。**
