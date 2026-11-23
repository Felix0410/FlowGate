# FlowGate V12-D Final｜全项目最终性能、可靠性、发布与验收体系设计

> **最后一个阶段，不再新开业务功能阶段。** 以`feat/v12-c-promo-cache@678701973a077d2dd6484405c91184e4f082fe18`为完整既有业务基线；把V11-D历史实验、M8/C人工验收、C Outbox P1、全项目压测、故障/安全/产品/运维/分支收敛**一次性**纳入V12-D Final实施合同。功能实现、压测、真实审核、正式部署/合并与真人手测目前**尚未执行**。权威事实见[审计基线](../reviews/v12-d-final-preflight-2026-11-23.md)。

## 1. 项目应交付的唯一业务全链

1. 用户：`/portal/`注册或登录→开发者空间→自己APP→查询公开已上架API商品/套餐（Redis缓存）→免费领取/普通付费/限时抢购3条路径→唯一BASE Slot和同月额度→签发APP凭证→真实双Gateway HTTP计量→月余额/用量日报/订单和退款状态。不能把模拟支付包装为真钱交易。
2. 运营：`/console/`通过真实ADMIN操作服务、API、动态路由、保护策略、发布/回滚、套餐、订单、活动、Rabbit消息与异常对账；OPERATOR/VIEWER只做获准动作，Portal用户永不获得运营权限。
3. Gateway：发布快照、身份、Redis分布式多维令牌桶、每实例并发限制、Resilience4j熔断/超时/失败状态、MySQL授权额度+唯一Ledger准入都继续工作，旧V11`V11_APP`与V12`V12_APP_API`额度账本独立。**不新增Sentinel/Nacos。**
4. 异步：RabbitMQ同Broker四条互相隔离的逻辑链：V11 Legacy Usage、V12 API Usage、B Payment Commerce、C Promo Intent，每条持久主/quorum重试/Poison、publish confirm+mandatory return、DB Outbox与Consumed、DB COMMIT后ACK。当前构建不再需要Kafka。故障恢复只读/补原MySQL事件，不凭空重扣/重放HTTP业务请求。
5. 数据：MySQL余额、订单、权益、已售/预留库存、Ledger/Outbox/Consumed/日报权威；Redis仅负责缓存公开投影、限速TokenBucket、抢购Lua软候选，不是库存或Quota权威。C付款`reserved--,sold++`同事务，已付款退款**sold不回售**。

## 2. 当前必须修的P1：C Outbox重试上限后可恢复

源码基线`flowgate-control/src/main/java/com/flowgate/control/promotion/PromotionService.java`：
```text
claimOutbox(): 只认 NEW / FAILED / 租约过期的 IN_FLIGHT 且 attempt_count < 8
failed(): 状态 FAILED, attempt_count已在claim中增加
replay(): 仅认 SENT 超过300秒且未Consumed
```
8次连续Rabbit发布失败后，事件永久停留`FAILED attempt_count=8`，新发布者不再领取、现有ADMIN补投也拒绝；这不是“至少一次投递最终必达”，必须修复。

**首选最小变更，不需扩表**：
- 在运营查询`GET /admin/promotions/{id}/events`中将`FAILED && attempt_count >= 8 && 消费记录不存在`明确映射为**`RETRY_EXHAUSTED`派生状态**；原DB原始列仍`FAILED`，保持兼容，不能谎称发送成功。记录独立高可观测告警，不能只在warn日志里等人发现。
- 原`POST /admin/promotions/events/{eventId}/replay`拓展对两种情况的ADMIN审计恢复：`SENT`超过300秒且未Consumed（保留原逻辑）；以及`FAILED`且`attempt_count >= 8`经过**有界冷却期**且未Consumed/未被其他worker租约持有（取件时DB status/attempt CAS原子可重复）。事件要满足Outbox event_id/payload fingerprint/Intent源状态/活动固定报价与月份，不允许替换eventId、改原金额、追加新Intent或消费历史；同eventId多次补投幂等、管理员前后角色/审计真实验证。`FAILED_PERMANENT`坏JSON/权威冲突不能因重试次数到了而自动恢复，只能改代码/排查后由清楚的另一个人工流程处置。
- `NEW/FAILED`自动取件仍有上限，极端长故障不打满Rabbit；重置耗尽计数时必须在ADMIN有审计的数据库事务内做有界1次“开启下一批”，且处理器继续publisher confirm/return/Outbox lease而不是直接发MQ。
- 数据恢复竞态：`SENT!=Consumed`。C Intent若已过期、或已有唯一Order，在相同原事件补投后应`IGNORED_TERMINAL/ALREADY_APPLIED`并DB提交后ACK；绝不复活过期订单/多预留/多付款。已Consumed必须拒绝/安全幂等，不允许误把Poison修复的event当初始库存。
- Test1：在全新隔离Rabbit断连累计至少8次，原MySQL Outbox处于FAILED/8，停止/重启Rabbit后不会永久卡住：ADMIN真正补投后1份Consumed、一张或零张（因过期）订单，Stock/Slot完全一致，原eventId不变；Test2：两ADMIN并发补投只有一方成功；Test3：300秒SENT实际冷却+进程启动冷却，别只mock时钟；Test4：INVALID坏消息仍Poison不可假自动恢复；Test5：Proxy Redis/DB停恢复与C OFF不会暴露未授权API。全部实测日志入证据。

## 3. 指标与实验：不虚构压测成绩

**唯一原则：先建立冷/热/故障基线，再做有限、经EXPLAIN证实的改动，复测同负载同硬件并保留真实数据。** 真本机CPU/内存不足到达目标时停下并注明`NOT_REACHED`，不要无上限并发破坏用户电脑。

### 3.1 Gateway四组对照（承接旧V11-D）

| 组别 | 请求路径 | 必须隔离的变量 |
|---|---|---|
| A DIRECT | 客户端直接Demo上游 | 不经过Gateway，不计算授权开销 |
| B PROXY | 经Gateway，无限流/熔断/配额 | 只包含Gateway代理网络/Netty开销 |
| C GOVERNED | Gateway开启Redis RATE_LIMIT、本机CONCURRENCY、Resilience4j故障保护（非故障子实验时不触发拒绝） | 提供充分速率预算，实测治理本身开销；单测429/503另列 |
| D METERED | 在C上增加V11_APP或V12_APP_API显式MySQL配额准入（**两种模式分列**） | 配额充分、避免自然耗尽污染业务成功延迟；真实MySQL Ledger/Outbox，身份授权开销明确披露 |

每组相同Demo`fast/slow/compute`路由/HTTP方法/响应字节与payload、固定请求到达速率、最大Client in-flight和相同预热/停顿/运行时长；至少2轮、随机或交叉运行顺序。将**计划请求数/已发送/客户端主动丢弃CLIENT_SHED/完成/成功/拒绝/超时**分别统计，吞吐用实际完成值而非已计划速率；统计业务成功和所有HTTP两类p50/p95/p99、429/503/504、upstream 5xx、quotaHop P95、Gateway CPU/RSS/线程、Control/MySQL连接池与Lock waits、Redis hits与Rabbit lag/Poison（替换历史Kafka lag）。比照原`docs/progress/v11-d-acceptance.md`，但当前系统只Rabbit，绝不能启动Kafka完成历史测试。

### 3.2 SQL 10万+条与稳定分页

只向**全新、用固定随机种子的隔离MySQL**插入≥100000条脱敏`fg_quota_ledger`及合理对应的`fg_outbox_event/fg_usage_daily`、audit样本（外键和索引必须满足，不能非法伪造Ledger让一致性比较失真）。分别对V11与V12账本/订单/活动列表/事件查询按APP/API/period/UTC time/status运行无界拒绝和稳定(cursor`created_at,id`)分页；真实`EXPLAIN ANALYZE`记录扫描行、实际时间、p95、索引大小、写入影响，对比offset深页。按查询路径选择最少联合索引，先同数据同资源基线，再补必要索引，无慢SQL证据不做大改表；已存在的历史账本列不可重写。与性能实验区别缓存命中与DB查询优化，不能拿cache hit代替10万SQL实测。

### 3.3 C抢购与缓存

- C旧真实证据：100库存/120隔离身份，成功100、订单100、收据10、Grant10；约98 QPS小样本。D需在**独立的新促销活动和新身份**逐级测试 1000→5000→20000 **HTTP请求数目标**，控制并发阶梯如24→64→128（根据机器承受能力调整）。这些不是20k QPS要求。每轮附真实库存总量/accepted/拒绝/失败/超时、唯一Intent与Order/Consumed/Payment/Grant/Stock总和，压测后对账零`INTEGRITY_GAP`。测试前必须有明确针对机器资源的安全上限；超出则记录NOT_REACHED而不是无限压。
- 缓存冷读/热读/代次失效/热点失效、404负缓存/穿透、雪崩/击穿与Redis断连回源限流，分别记录公开投影DB_LOAD与**全MySQL请求计数两种不同范围**、Redis hit/miss/负缓存、p95、服务线程数/CPU/RSS。已存在的热读1488 QPS仅是C证据，不能当D新基线。
- Redis epoch恢复必须经历 MySQL活动`REBUILDING`+epoch++，旧候选/补偿不能污染新softRemaining，DB COMMIT结果未知不能盲目Redis返还；真实Redis重启/FLUSHDB/NOSCRIPT只在自有隔离实例。
- 重新验证100份抢购与普通B/免费A/旧V11同月APP Slot竞争，付款vs过期/取消、已用退款409、已退款sold不回售、跨UTC月策略；双Control/双Gateway与多Worker。

## 4. 真实故障矩阵（每类都有SQL核账）

| 故障/交错 | 结果 |
|---|---|
| Redis关停、重启、FLUSHDB、Lua NOSCRIPT | 抢购新请求503；MySQL已受理可查询/开通；恢复以epoch重建，无假超卖；公开查询有限回源 |
| Rabbit关停、重启、持卷恢复、Confirm Return、TTL主/Retry/Poison、坏JSON | V11/V12/订单/促销四队列彼此隔离；Outbox NEW/FAILED不丢，原eventId恢复 |
| Rabbit Outbox FAILED达到8、SENT未Consumed 300秒、COMMIT后ACK丢失、100重复 | 可观测告警/ADMIN审计、一个Intent一个Order一个Grant，迟到终态只被忽略，不能无限丢件 |
| MySQL暂停/重启、双Gateway额度竞争、Quota事务COMMIT后HTTP超时 | 没有重复chargeId/超过额度、Ledger/Outbox/Consumed/Daily eventually reconcile，恢复前fail-closed |
| 付款vs超时/取消vs模拟退款vsGateway扣额度 | 唯一状态机获胜，收据/Grant/库存/额度一致，不倒改历史已用额度 |
| 动态配置发布失败/回滚、保护策略版本切换、旧Gateway流量、CB半开 | 不出现绕过认证/计量，历史在途请求释放原并发池；故障5xx/超时有真实fallback错误码 |
| 只读角色尝试admin写、Portal跨用户APP/订单/Intent、伪造User/Price/Stock、Token泄露 | 401/403/404/409明确且无越权；截图/日志/README不得泄露密钥 |
| 浏览器1280/375、键盘、慢网/重复点击 | 产品关键闭环可独立完成，支付/模拟退款语义明确，错误态、加载态、空态真实 |

4条Rabbit链要对账：旧`fg_quota_ledger/fg_outbox_event/fg_consumed_event/fg_usage_daily`、V12`fg_api_quota_ledger/fg_api_usage_outbox/fg_api_consumed_event/fg_api_usage_daily`、B`fg_order/fg_payment_record/fg_business_outbox/fg_business_event_consumed/fg_entitlement_grant`、C`fg_promo_stock/fg_promo_purchase_intent/fg_promo_outbox/fg_promo_event_consumed`。不能把`SENT`当Consumed，不能把MQ队列空当对账通过，范围>1000的C审计是UNKNOWN，今日未封账是PROVISIONAL。

## 5. 观测、性能优化、UI与部署

- 先用现有Micrometer/Redis计数与MySQL`performance_schema`、Rabbit Management真实指标；如果需要新增极少量测量代码，限制**低基数标签**（routeId、固定outcome/queue，不允许token/userId/原始URL）和有界内存，既有Console可展示四链消息欠账、可运维错误码和最后实测时间。不要为造一个“漂亮看板”引Prometheus/Grafana/K8s/Nacos/Sentinel新依赖；外部监控部署不是首版门槛。
- SQL/缓存/网关线程与熔断只针对测得瓶颈最小修复；每项PR写基线→改动→同负载对照→潜在副作用→rollback；无法改进也要诚实展示限制。Gateway已有`ReactiveTokenBucketLimiter`和`CircuitBreakerConfigRegistry`，禁止叠第二个默认激活的限流熔断框架。
- Portal/Console必须使用现有业务导航、用户可理解的工作台；不堆标语、假KPI、假“就绪”与假库存。同步中文指南/服务就绪解释、无SQL/JSON/DB id输入的核心演示；错误401/403/404/409/429/503/504与权限变更能被用户理解。
- 隔离复现脚本须提供`doctor/up/status/down`只触摸本轮owner资源，默认OFF无表时旧V11/M/B仍正常。对真实常驻环境只提供**只读部署审查+手工维护步骤**（备份→版本/迁移/全节点就绪→发布开关→回滚），不执行用户已有数据库/原卷变更。所有密码/Token脱敏，演示测试账号保留在gitignored私有文件，不提交真实凭证。
- 安全、性能与真实业务三套报告分别记源码SHA、环境指纹、数据生成种子、Jar SHA、执行命令/exitCode、首次失败/修复、NOT_EXECUTED/NOT_REACHED、负载CSV/JSON/截图索引；不得只记`passed:true`替代原始证据。

## 6. 统一验收口径（一次性完成，未签字前不标CLOSED）

退出V12-D Final必须同时满足：P1 Outbox耗尽真实恢复；PR重复历史的安全处置方案被明确记录；Java17/Node/Python/真实HTTP/浏览器两视口和4 Rabbit链实际回归；V11旧5次200+第6次429与V12 A/B/C真实闭环；SQL≥100k与4组公平实验有实测数据或**明确受资源限制NOT_REACHED且负责人书面批准降级**；C缓存/抢购负载和Redis/DB/Rabbit故障有权威核账；根.env/常驻卷未动；README/AGENTS/迁移和运行文档与代码一致；最终`docs/acceptance/v12-final-results.md`和`docs/progress/v12-d-final-implementation.md`真实归档；独立源码复审与负责人真人验收的状态各自标明。

**只有自动化、实测与负责人签字全部满足时才能标正式“开发与验收完成”；否则保持Draft和`PENDING_ACCEPTANCE`，但不再分割成新开发阶段。** 不自动merge main/tag/生产部署。
