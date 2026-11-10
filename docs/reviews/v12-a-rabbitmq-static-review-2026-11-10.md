# FlowGate V12-A｜Portal与RabbitMQ远端源码/证据复审（2026-11-10）

> **本轮方法**：读取PR #15真实远端HEAD `472d9eb02a49c9a9384656bf1acd2b18ac9e30d9`（功能提交`af071fd9e8f8da85d705d35ccf64bf03cfd064e4`）的Java、SQL、RabbitMQ拓扑、Portal ESM/Console、Python隔离Smoke、JUnit和3份脱敏证据。**未亲自重新执行JDK17、Docker MySQL/Redis/Kafka/RabbitMQ、真实Chromium或压测**。本报告中的“已验证”特指Codex提交的实测证据与源码路径可相互核验；不能宣称本轮ChatGPT单独跑通。
>
> **阶段总体裁定**：V12-A A0～A6**已编码并有目标业务闭环证据**，不是只设计；**A7最终验收有条件暂缓**，以下P1为需要轻量补丁与有针对性实测的可靠性/旧环境友好性缺口。本报告不要求退回重做Portal，也不要求过度压测或直接开发B/C。

## 1. 远端分支/实测范围

- PR #15 `feat/v12-a-portal` → `docs/v12-platform-business-redesign`，Draft。只有2个新提交，包含`sql/flowgate-v12-a.sql`十四表、`/portal/`独立用户端、`/admin/market/**`、`/internal/quota/charge-v12`、V12新Ledger/Outbox、RabbitMQ Quorum重试死信链路及真实隔离启动器。旧V11-C Kafka文件/历史表未改。
- 脱敏证据：`docs/progress/evidence/v12-a-targeted-checks.json`：**初版**Java 78个（Contract11、Control37、Gateway26、Demo4），Rabbit代码接入后只定向执行6个（4个新Rabbit测试+2个重跑Portal），共**82个不同测试**但**Rabbit接入后完整82个全量NOT_EXECUTED**；Node3入口、Python14项；最终Maven重新打包跳过测试，不混写。
- `docs/progress/evidence/v12-a-functional-smoke.json`：真正隔离双Gateway、MySQL8.4、Redis7、V11 Kafka、V12 RabbitMQ3.13.7、Control/Demo；API路由发布/节点应用→商品上架→两个用户/应用→8并发领取1成功7冲突→APP调用Free5=5×HTTP200 + HTTP429，`balance.remaining=0`、`Ledger=Outbox=Consumed=Daily=5`、原V11 LegacyLedger=0、重复Rabbit消息不双聚合、今日对账PROVISIONAL。新隔离服务已停、卷保留，根.env未动。
- `docs/progress/evidence/v12-a-browser.json`：真实Chromium1280宽与375 DOM宽检查、注册/建APP/领免费权益/单次Key/授权与日报/退出；**没有保存375截图**，有真实1280和1498键盘截图。真人E6与V12-A7仍NOT_EXECUTED。
- **没有跑**：100并发领免费权益、双Control注册、Rabbit断网/重建/死信/TTL重试全矩阵、原V11 B23/C26真实故障回归、封账日MATCH、10万SQL和四组压测。用户已有数据卷、IDE/运行端口和main未迁移/合并。

## 2. 实际实现的主要正确性

1. Portal独立`PORTAL_USER`/Redis摘要Session，并在请求路径用`PortalAuthFilter`校验；User/DeveloperMember/APP的归属SQL、管理ADMIN和Caller/APP密钥用途独立。Portal前端在JS内存保存登录Token；凭证只在同页面单次回显，离开清空。
2. 商品可售基于原API真实私有已发布路由和全部接流节点能力，不让用户填JSON/策略ID；SKU只允许本月免费BASE，领取MySQL事务锁APP/权益槽+每人API终身唯一Claim/Grant/Subscription/Balance；旧V11应用级月账本不被重写。
3. Gateway可信schema2+V12计量模式、内部WebClient非阻塞、旧schema1兼容；真实双Gateway 5/6和仅V12 Ledger证据齐全。
4. V12 RabbitMQ复用**Rabbit Broker而非旧Kafka消息协议**：`ApiUsageRabbitConfiguration`新exchange、quorum主/TTL重试/毒消息队列，`ApiUsageWorker`publisher confirm+mandatory returns，原事件ID保留、DB COMMIT后手动ACK；V12独立日统计/消费表。**RabbitMQ3.13 quorum的at-least-once死信需要x-dead-letter-strategy+reject-publish及stream_queue feature flag真正启用**，当前源码配置前两项，运行态feature flag未作为E2E证据；参照RabbitMQ官方3.13文档，实际需检验Broker能力。
5. B/C商业订单/支付/抢购不在本PR：正确保持未实现。

## 3. P1：应在进入V12-B前解决的定向可靠性缺口

### P1-A01｜永久非法JSON可能被Rabbit当临时依赖故障无限回炉

**源码**：`flowgate-control/src/main/java/com/flowgate/control/portal/ApiUsageGrantedEvent.java`直接`SnapshotCodec.JSON.readTree(payload)`；`ApiUsageWorker.receive()`只把`ApiFailure|IllegalArgumentException`判为毒消息，其他`RuntimeException`都当数据库暂时不可用，推到TTL重试队列并ACK原消息。**Jackson 3的`tools.jackson.core.JacksonException`继承`RuntimeException`而非`IllegalArgumentException`**；因此语法错误JSON引发的Jackson异常会落入重试分支，永不进入毒消息隔离队列，消息可能无限重投。另`ApiUsageWorker.publish()`遇到Outbox本身永久错误payload时只记`RABBIT_PUBLISH_UNAVAILABLE`并把FAILED无限重领，没有永久隔离/明确告警。

**修复合同**：在事件输入边界统一把Jackson反序列化/严格字段违反/永久权威冲突映射为指定`InvalidUsageEvent`或确定性的ApiFailure；Consumer只对真正依赖暂不可用（DataAccessResourceFailure/网络等）持久重试，未知异常用明确的有限重试/隔离与告警，不因为非DB异常永久回炉；Outbox malformed独立FAILED_PERMANENT/原因码，不作为“Rabbit网络故障”重试。须保证所有异常记录不包含原文payload/APP Token；待补投操作复用原事件ID且审计。

**最少证据**：真实隔离Rabbit入队一个语法错误JSON和一个有效格式但Ledger/Outbox冲突消息，证明它们进入Poison且不会在5s后反复返回；再做一次MySQL可恢复瞬断，证明确属临时故障仍自动重试且恢复后只一次聚合。新增JUnit定向分类测试。

### P1-A02｜Rabbit关键故障恢复只做了mock/成功路径，可靠性交付暂不能认证

**源码/证据**：`V12RabbitWorkerTest`只有4个Mockito测试：ACK顺序、ApiFailure毒消息、确认重试副本后ACK、确认NACK不ACK；真实Smoke里Broker在线、重复事件与5笔追平，**没有实际Broker停机恢复/Publisher confirm失败/mandatory return、quorum队列持卷重建、Retry TTL 5s→主队列、死信隔离的端到端检验**。配置采用浮动镜像`rabbitmq:3-management`，实测版本是3.13.7；不能把“用了quorum配置”当作经过真实故障证实的持久可恢复。

**修复合同**：只对新隔离Rabbit容器及其本人卷进行最小真实故障注入：①已提交Ledger/Outbox但Broker不可用时Outbox仍NEW/FAILED可重投、授权仍按定义返回；②容器重建且保留自己的Rabbit卷后消息存续，恢复后Consumed/Daily只增加一次；③语法毒消息真正到Poison队列，且不会ACK未知结果；④真实MySQL短暂故障产生TTL重试副本/confirm后ACK原件，恢复只1次累计。隔离Rabbit拓扑读取实际queue type、参数与feature flags，不用假Mock。建议测试演示镜像绑定固定版本或digest，文档明确单节点Quorum仅持久性不提供真正多Broker多数派HA。

**最少证据**：追加`docs/progress/evidence/v12-a-rabbit-review-smoke.json`，含隔离owner、版本、各故障时间/退出码、Before/After状态/消息计数/账本一致性；失败尝试保留。无需D级大型性能压测。

### P1-A03｜默认OFF且未迁移新14表的旧Console，新增市场页直接读新表

**源码**：`MarketAdminController.categories()/listings()/reconciliation()`无`FLOWGATE_PORTAL_ENABLED`/V12 Schema门禁；`MarketCatalogService.categories()/adminListings()`直接SELECT `fg_api_category/fg_api_listing`；Console导航固定新增市场入口。旧V11默认不开V12、没有新14表，进入新运营页会产生SQL表不存在的5xx。其他旧Console功能大概率仍能运行，但这违反“默认OFF功能显示明确待就绪，不做半成品/不误报数据库崩溃”的用户体验目标。

**修复合同**：新增只读、无新表依赖的`/admin/market/readiness`（或同等）显示`DISABLED/SCHEMA_NOT_READY/READY`；页面先读它，关闭或缺表给可理解的下一步与文档链接，不继续读缺表；ADMIN新写接口仍默认OFF阻断，禁止无授权迁移真实用户库。不影响旧Console原有20功能。真库验证旧V11未迁移时访问页面不500，以及独立V12已Ready后完整可操作。

## 4. P2及后续债务（优先级低于P1，不能当成已经完成）

- **P2-A04**：`ApiUsageService.replay()`只按Outbox.SENT保存的`next_attempt_at`判断等待300s，缺少类似旧C在进程启动后300s的冷却；建议对补投加入启动冷却并测试双ADMIN CAS；不是“重复扣余额”的证据，消费幂等仍存在。
- **P2-A05**：`ApiUsageService.reconcile()`的3个全表COUNT和首10001笔扫描虽会在超限返回UNKNOWN，但前置全表关联可能造成高开销；交给V11-D/V12-D实测EXPLAIN与有界索引诊断。当前只证实5笔。
- **P2-A06**：`rabbitmq:3-management`未固定版本；真实Broker3.13.7，quorum目前单节点不是HA；长期应制定与Boot4/SpringAMQP的版本矩阵/更新计划，不擅自修改用户现有集群。
- **P2-A07**：未来B/C设计文本大量写Kafka；因负责人选V12 RabbitMQ，必须以新[消息中间件修订设计](../design/v12-rabbitmq-adaptation.md)为准，**V12-B订单与V12-C抢购消息统一RabbitMQ独立Commerce Exchange/Queue**，而旧V11-C Kafka保留兼容，不对旧用户持久Kafka做直接迁移。
- **待人工**：Portal Free5、表单/加载态、Rabbit读模型、Console运营页的真人1280/375手动使用；V11-E6与V11-D未完成原样保留。

## 5. 阶段裁决、停止线

**A0-A6功能编码+定向证据通过静态核对；A7等待P1-A01～A03轻量修补与真实Rabbit故障证明，以及负责人真人体验。** 本轮不直接修改业务Java/SQL/JS，不重写旧MQ，不扩大功能范围。修复任务合同详见根目录`CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md`。完成后再次比对新远端SHA、核心源码与脱敏证据，再决定是否放行V12-B；不要自动开启Rabbit/Kafka或迁移/合并main。
