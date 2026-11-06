# FlowGate V1.1-C：Kafka、Outbox、异步用量、对账补偿（Codex 编码合同）

> 状态：未开始，必须在 A/B 真实已实现后开始。本块是 **MySQL 账本 → Outbox → Kafka → 用量读模型**，绝不可将 Kafka 改成配额扣减的唯一事实来源。每条命令和实际测试证据按本块进度末尾回填。

## C-00 业务定义

每条 fg_quota_ledger 表示已授予的累计配额；同一事务已经写有 fg_outbox_event 的 USAGE_GRANTED 事件。异步消费者生成按 app、API、UTC 日期的 fg_usage_daily 汇总，并允许运营人员查看最近用量、事件堆积和对账差异。异步统计可能稍晚，但用户当期**权威余额**永远从 MySQL fg_quota_balance 取，绝不从 Kafka/聚合视图判断能否再次准入。

## C-01 依赖与数据库

1. 仅在 flowgate-control 的 pom.xml 引入与当前 Spring Boot 版本兼容的 Spring for Apache Kafka 依赖；Kafka 单节点仅用于本机 compose.local.yml（增加启动 profile/可选项），不另起一大批 Java 微服务；旧 V1 无 Kafka 时仍可正常启动。先验证实际 BOM 与 Boot4 兼容，不凭记忆编版本号。
2. 表 fg_outbox_event 在 B 块已建立；本块增加 fg_consumed_event(event_id PRIMARY KEY,processed_at)，fg_usage_daily(day_utc,application_id,api_id,units,request_count,updated_at,PRIMARY KEY(day_utc,application_id,api_id))。查询需有时间范围与 app/api 组合索引。
3. 事件结构 version=1、eventId、chargeId、applicationId、apiId、periodUtc、units、eventTime；Kafka topic flowgate.usage.v1，message key 为 appId，禁止透传 Token、HTTP Body、Header、IP 等敏感字段。

## C-02 可靠投递服务

1. Outbox worker 可配置开关、批次上限、并发上限与退避策略。使用 MySQL 的 SELECT ... FOR UPDATE SKIP LOCKED **仅在短事务中领取可投递事件**，立即写 leaseOwner/leaseUntil（如 B 建表未预留，则通过兼容增量 DDL 补）；快速提交事务。不能开一个数据库事务一直持锁等待 Kafka send()。
2. Kafka ack 成功后按 id+leaseOwner 标记 SENT；失败按指数退避更新 next_attempt_at、attempt_count，超过阈值到 FAILED 并暴露可诊断原因码（脱敏）；租约到期可安全重领，不允许永远 IN_PROGRESS。
3. Kafka 可能已收消息、进程却在标记 SENT 前崩溃；因此允许消息重复投递，**绝不承诺 Kafka+MySQL 跨资源 exactly-once**。生产者用合适 ack/retry 设置并在集成测试中模拟此场景。
4. 控制面业务线程不等待 Kafka 完成：计量主事务与本地 Outbox 写入成功即可返回配额授权；Kafka 宕机只影响异步统计，Outbox 堆积应可见且可限流/告警。

## C-03 消费与幂等

1. 消费者收到事件，在**同一个 MySQL 事务**中 INSERT fg_consumed_event(event_id) 与 UPSERT fg_usage_daily 累加 units/count；若 eventId 已存在，跳过累加。
2. 只有业务事务提交后才向 Kafka 提交 offset；已写数据库但 offset 未提交时，消息重放不会再次加总。避免在异常 catch 中直接 ack、避免超过尝试上限静默丢弃。
3. 消费失败带可解释的 Retry/Dead Letter 路径。重放失败事件必须复用原 eventId，防止一次重投产生第二次计数。
4. Consumer 可停机和恢复、重新平衡；自定义线程池/任务并发有上限，不能把阻塞数据库消费交给 Gateway Reactor 事件循环。

## C-04 对账及人工排障

1. 用 fg_quota_ledger 权威 SUM(units) 按 day/app/api 与 fg_usage_daily 对比；在 worker 积压时应把“尚未消费的 Outbox”作为可解释延迟，而非立刻修改余额。
2. 管理端 /admin/usage 支持 app、API、日期区间与游标分页；/admin/async-events 支持 NEW/LEASED/SENT/FAILED 与积压数；/admin/reconciliation 支持指定时间段校验差异与幂等修复读模型。限管理员/运营；VIEWER 只读；所有手工重放记录审计。
3. 校验不得用非事务快照把实时新增 Ledger 和聚合视图相比较而报错；只对关闭的日期区间或固定 watermark 对账，记录 cut-off 时间和 lag。
4. 定向人工排查能够追踪 eventId→chargeId→ledger→daily aggregate，不需要读取应用 Token 或原始 API 数据。

## C-05 定向故障用例

- Kafka 停止：内部扣减仍 200/429、Outbox 增加、统计延迟明确；Kafka 恢复后事件被送达，聚合追平。
- Kafka ack 成功但更新 Outbox SENT 前故意中断：重复发送，fg_consumed_event 只存一次，fg_usage_daily 不重复累加。
- DB 聚合提交后、不提交 Kafka offset 时退出 Consumer：重放后汇总不变。
- 消费者处理失败可重试；不可恢复错误进入 FAILED/DLT，运营端可查看、手动安全重放。
- 双 worker 并发领取、超时租约回收，不重复扣 MySQL 额度、不无界堆积；月末事件日期取 Ledger 冻结 UTC 事件时间。
- 同时验证 2 个应用各 5/20 单位的账本总和与汇总一致，发布事件不携带 Token；未计量请求不依赖 Kafka。
- Node 产品页定向测试 + JDK17 Control 测试 + 隔离 MySQL/Redis/Kafka 真实集成；脱敏证据存 docs/progress/evidence/，执行后回填。

## C-06 不做

不新增 Kafka 到 Gateway 依赖，不在 HTTP 热路径同步等待 Kafka ack，不建设通用订单业务，不把异步用量称为实时权威账单，不用 Redis 的 key TTL 代替 Outbox 幂等/持久化。


## C-07 审核后执行分配（A/B未通过时不得运行）

| 阶段 | 工作内容 | 验收门槛 |
|---|---|---|
| C0 | 从B权威Ledger/Outbox实测中冻结事件Schema V1：eventId/chargeId/appId/apiId/period/units/time/version，脱敏；核对B已预留leaseOwner/leaseUntil | Outbox/ledger同事务事实成立 |
| C1 | Control可选Spring Kafka依赖、单机可选compose profile、环境开关；旧本地MySQL/Redis无Kafka仍能启动 | 默认关闭Kafka时V1路由和B授权仍正常 |
| C2 | Mapper + Worker：`SKIP LOCKED`小事务领取有限批次、写leaseOwner/Until、commit后才send；ack成功/失败按租约owner状态转换，超时重新领取 | 双Worker无长期持锁；send成功DB更新失败可重复投递 |
| C3 | `fg_consumed_event`唯一eventId + `fg_usage_daily`UPSERT同事务；DB提交后ack offset | 消费/ack中间崩溃重放不双计数 |
| C4 | 管理端`/admin/usage`、`/admin/async-events`、`/admin/reconciliation`，固定watermark/已关闭UTC日期对账、失败安全重放/审计；前端真实表格和中文指南 | Kafka停机时显示“统计延迟”，余额从DB权威读；不以lag=0代表未失败 |
| C5 | Java/Node/隔离Kafka+MySQL/Redis真实故障脚本，脱敏证据和阶段SHA | 停Kafka→授权仍成功/outbox堆积→恢复→对账；重复事件只计1；最终FAILED可诊断 |

**C阶段不做新Gateway Kafka客户端**，不等待Kafka ack才放行、不重新扣余额、不将`SKIP LOCKED`当权威额度查询、不无界提交失败事件。Outbox状态及lease owner更新必须有条件where，不被迟到worker覆盖。全量性能留D。
