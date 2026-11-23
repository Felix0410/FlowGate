# V12-C｜高并发套餐Redis缓存设计与恢复合同

> **必须实现的C核心技术点**：所有已上架API套餐的公开列表/详情/报价读缓存、活动详情缓存、限时活动Lua候选名额与双Control缓存一致性。设计参照Redis Cache-Aside（https://redis.io/docs/latest/develop/use-cases/cache-aside/）及Spring Data Redis脚本执行机制（https://docs.spring.io/spring-data/redis/reference/redis/scripting.html）；本轮未执行任何Redis缓存测试。**Redis只加速读和过滤候选，MySQL才是库存、价格、身份和权益的权威。**

## 1. 分层目标与现有代码嵌入点

- **入口**：`MarketCatalogService.categories/search/detail/offers`当前直读MySQL。新增`CatalogCacheService`只缓存*非个性化的公开商品字段*，将当前`MarketCatalogService`拆为：DB-public投影查询、缓存包装、实时资格补充。缓存命中不能直接给普通用户`purchasable=true`，应从Readiness/API当前可用性动态补充，**OrderService.create`与B MarketCatalogService.paidOffer()/claimOffer()仍从MySQL锁行重验报价**。包装不能把DB强一致的`FOR SHARE`替换成读缓存。
- `PromotionPublicCacheService`缓存已发布活动的ID/商品名/公开金额分/单位/目标月/活动窗/总库存上限、status/epoch/version。不要缓存owner、订单、真实库存reserved/sold精确值、Payment/Grant/Token/个人可购买资格。
- `PromotionCandidateGate`用Redis Lua管理softRemaining和请求级租约。只授权短暂进入**MySQL受理事务**的资格，不生成订单或库存事实。抢购Lua有错误必须503拒新受理，不“容错”绕过Redis，把2万并发全部冲进单Stock行。
- 仍只用Spring Boot已有`spring-boot-starter-data-redis`+Spring Data Redis `StringRedisTemplate`/`DefaultRedisScript`、一份资源Lua脚本（放`flowgate-control/src/main/resources/redis/promo-candidate.lua`及`promo-compensate.lua`），不引Caffeine/Redisson。Script SHA1启动时缓存、NOSCRIPT自动EVAL兜底由Spring Data Redis原脚本执行器负责；必须真实验证Redis重启/FLUSHDB后的脚本重装。

## 2. 缓存键契约、TTL与边界

| Key类型 | 建议命名 | TTL/容量策略 | 是否可接受陈旧 |
|---|---|---|---|
| 公共分类 | `fg:c:catalog:v1:categories` | 120s ±30s；一次最多100分类 | 是，最多TTL后必回源 |
| 公共商品列表 | `fg:c:catalog:v1:list:<sha256(normalizedQuery,category,cursor,limit)>` | 20s ±5s，热门首页/分类前3页优先缓存，最深`afterId`不缓存 | 是；分页商品上下架可能短暂变动，交易校验DB |
| 公开商品详情 | `fg:c:catalog:v1:detail:<listingId>` | 30s ±10s，限制单值字节大小32KB，docText超限不缓存 | 是；不能拿旧详情决定是否购买 |
| 公开套餐/价格列表 | `fg:c:catalog:v1:offers:<listingId>` | 20s ±5s，含不可变sku报价、单位、period_policy；不含用户态`purchasable` | 是；当前有效月/付款窗必须实时计算 |
| 活动公开页 | `fg:c:promo:v1:public:<activityId>`及`:list:<hash>` | 3～5s±1s，跨活动起止点截断有效TTL | 是；标明显示状态非最终准入 |
| 已知不存在详情 | 同cache key加内部`NOT_FOUND`标志 | 3～5s，限制负缓存总量；只对确定的404，不缓存401/403/5xx | 是；不让恶意ID形成永久负缓存 |
| 热点回源单飞锁 | `fg:c:catalog:lock:<sha256(cacheKey)>` | 800～1500ms，UUID token，Lua按token释放 | 只用于节流，不是订单锁 |
| 公共缓存代次 | `fg:c:catalog:gen:<category/listing>` | 不短于数据TTL，或键中带版本+监控；避免慢回源覆盖新发布 | N/A |

限流和key数量：`q` normalize Unicode/trim、最大50字符，category必须合法，分页`limit<=20`、`afterId>=0`且只缓存可枚举的热点前页；任意关键字只缓存命中率值得缓存的组合，拒绝将认证Header、Token、用户ID、未脱敏手机号写入key。随机TTL避免同一时刻大量到期，注意负缓存小TTL。对数据库异常只缓存**成功的公开空结果**，绝不把5xx当成“无商品”。

## 3. Cache-Aside安全读取、回源单飞与失效

```text
GET 公共详情/套餐 → 校验输入、开关 → Redis GET
  命中(版本/key格式/TTL合法) → 公开投影；实时计算有效月、业务Readiness/资格
  未命中 → token SET NX EX短锁（拿到锁者DB查询）
      → MySQL仅公开投影，带listing.revision/sku immutable
      → 检查当前generation未变化，原子SET EX（含TTL抖动）
      → 返回
  其它请求：短等50～100ms重查缓存、无法拿锁则受全局有界DB fallback semaphore
  Redis本身不可达：只读GET按并发许可DB回源，否则HTTP503 CACHE_DEGRADED
```

**管理员提交才使缓存失效**：`MarketCatalogService.category/save/sku/publish`和`PromotionAdminService.publish/pause/end`的MySQL事务提交后（afterCommit）删除相关detail/offers/list/search热点key或递增generation。必须确保无意的事务回滚不删除已发布的新缓存；失效失败记低基数错误码/审计并依TTL恢复。对于列表查询键无法枚举，使用`catalog:list_generation`滚代次（只为无敏感公开列表），避免KEYS全扫和海量DEL。**不要求缓存与MySQL强一致秒级**：商品下架/报价上新时展示可能旧20～30秒，但下单必须重新校验DB；后台显示“当前缓存版本/上次失效时间/失败”，不能掩盖失效失败。

**慢回源覆盖问题**：假定Reader R在版1的DB拿到旧内容；管理员在MySQL发布版2且Redis bump generation；R不能用旧gen写入当前cache键。Cache写入Lua比较`generation==expected`后才SET，失败丢弃而非覆盖。该机制是**提升公开显示一致性**而非交易强约束，generation Redis丢失/写失败仍由最长TTL兜底；缓存条目本身存schemaVersion/revision/expiresAt。需要防止`generation`短于旧数据生存导致旧值复活，校验TTL与generation共存，或活动/商品具体版本化key。不得为保证某一商品绝对即时一致而每次GET都查MySQL失去缓存价值。

**缓存击穿/穿透/雪崩**：热门SKU prewarm可在明确发布/首页读取时有界预热，首页公开目录可提前5s刷新（可选），不能预热用户私有数据；唯一热点锁防击穿，负缓存防不存在ID穿透，TTL抖动/热点错峰防雪崩；DB回源信号量或线程池容量门槛，超过则HTTP503，不让Redis故障拖垮MySQL权威准入。缓存降级故障**只影响展示/新抢受理**，已付订单和Gateway必须仍可继续。

## 4. 候选软库存Lua的原子动作

### 4.1 key与数据契约

建议统一短前缀 `fg:c:promo:gate:<activityId>`（单节点Redis7无cluster slot要求；将来集群化统一hash-tag）。在同活动scope下：

- `:epoch` = DB活动epoch（整数），`:state` = READY/REBUILDING/CLOSED，`:remaining` = 一个**软候选计数**（起初DB total-reserved-sold）；所有在活动外的信息不缓存到这里。
- `:candidate:<token>` = 活动候选令牌的短TTL标识，`:user:<opaqueUserKey>` = 本次principal的短租约（opaqueUserKey可为基于principal和activity的Hash，不含明文用户名/Token），TTL建议5s；token由Control生成的不可猜UUID，仅用于Redis补偿，**不是用户购买凭据**。
- Lua的KEYS必须全由服务端构造，ARGV只传由DB活动快照得到的epoch、随机token、短TTL/opaque user标记；不相信用户提供的库存/价格/epoch。脚本不得读写MySQL、调用Rabbit或对每次请求生成不同脚本源码；对外公开API绝不返回softRemaining为“真实剩余”。

### 4.2 Claim原子伪Lua

```lua
-- KEYS[1..5]: epoch,state,remaining,candidateTokenKey,userLeaseKey
-- ARGV: expectedEpoch,opaqueToken,ttlMs
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 'STALE_EPOCH' end
if redis.call('GET', KEYS[2]) ~= 'READY' then return 'CACHE_NOT_READY' end
if redis.call('EXISTS', KEYS[5]) == 1 then return 'USER_BUSY' end
local n = tonumber(redis.call('GET', KEYS[3]))
if not n then return 'CACHE_NOT_READY' end
if n <= 0 then return 'SOFT_SOLD_OUT' end
redis.call('DECR', KEYS[3])
redis.call('PSETEX', KEYS[4], ARGV[3], ARGV[2])
redis.call('PSETEX', KEYS[5], ARGV[3], ARGV[2])
return 'CANDIDATE_ONLY'
```

**不能把`CANDIDATE_ONLY`翻译为202**。只在MySQL stock/reserved+Intent+Slot+Outbox提交后HTTP202；同请求重试查MySQL幂等源而不是重复EVAL。一个用户若多次Lua被USER_BUSY说明请求正在处理中，先读MySQL，若无则提示稍后重试。

### 4.3 DB回滚后的带epoch/token补偿

```lua
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 'STALE_EPOCH' end
if redis.call('GET', KEYS[2]) ~= 'READY' then return 'GATE_NOT_READY' end
if redis.call('GET', KEYS[3]) ~= ARGV[2] then return 'ALREADY_COMPENSATED' end
redis.call('DEL', KEYS[3])
if redis.call('GET', KEYS[4]) == ARGV[2] then redis.call('DEL', KEYS[4]) end
redis.call('INCR', KEYS[5])
return 'COMPENSATED'
```

上述KEYS依次epoch,state,candidate,userLease,remaining。一次性删除candidateTokenKey使重复补偿无法二次INCR；同epoch检查防补偿旧候选错误地增加新活动库存。**只有明确证明MySQL事务已回滚才允许补偿；COMMIT结果未知（超时/断线）时先按同principal+requestId查MySQL，如果仍未知则不补偿、等待候选TTL/对账重建，绝不能把已提交名额误补回Redis**。如果DB已COMMIT而HTTP中断不能补偿；如果补偿网络失败，允许软库存**短暂少卖**，由重建恢复。候选键TTL到期后，无法再次安全补偿（不因为softRemaining减少就推断可恢复）；由30～60秒节流的对账任务在候选租约均过期且MySQL仍有可售名额、softRemaining却为0时自动触发安全epoch重建（重建工具单活动串行，不能多个Control同时重建）；定期epoch重建可以修复。**候选Lua写脚本应该短小、无慢循环或全量KEYS操作**，避免Redis单线程被阻塞。

## 5. Redis失忆与高并发重建（必须有跨DB时代次）

高风险是DB接受事务与Redis重建同时发生，不能`SET remaining=DB available`就宣布完成。为每个促销活动维护MySQL权威`fg_promotion(epoch,status)`，设计受控两阶段：

1. ADMIN/受控Rebuilder在事务`SELECT fg_promotion FOR UPDATE`，将活动临时改为`REBUILDING`，epoch++，记录原RUNNING/PAUSED目标态；提交。活动接收事务必须在APP/Slot锁之后`FOR SHARE`读取Promotion并校验原Lua epoch，已持有旧共享锁的交易会先提交/回滚，后来才允许epoch++，**失效旧Lua候选**。重建期间返回503，不走DB抢购旁路。
2. 在`REBUILDING`期间，读取MySQL `stock.total-reserved-sold`、已接受的Intent以及没有悬空的库存缺口；校验对账，无异常时把**新epoch、softRemaining、gate**通过Redis短Lua原子重建（执行失败继续REBUILDING）。Lua key设置活动窗口上限的合理TTL，不能在活动尚有效时自行丢失全部key而继续接受；到期/丢失让请求503并启动新重建。
3. 切回RUNNING之前读取Redis新epoch/remaining，DB CAS从REBUILDING(epoch=n)→RUNNING（或PAUSED），提交后**最后再开启gate READY**；新请求只有DB状态与Redis gate都就绪才能进入。若Redis在步骤2后再次丢失，状态不匹配的请求503并重新触发重建，不能绕行。
4. 切换期间到达Lua的旧epoch候选即使Redis返回成功，后续DB因epoch不相等而回滚；旧epoch候选补偿也不会误改新epoch。**Redis永远不反向写MySQL.stock**，库存恢复以MySQL已提交状态为唯一来源。
5. Redis持续不可用的处理：公共读缓存走有限DB回源（不能牺牲登录/订单/配额热路径），**抢购claim关闭**，原已受理Intent的timeout、查询、付款仍用MySQL并可让Rabbit继续处理；Redis恢复后用新的DB epoch重建活动，不依赖RDB/AOF快照的可靠性。

**已付款退款不返售**：`sold`为累计模拟付款确认，`reserved`只代表尚未付款的有效Intent/订单。Redis softRemaining重建使用`total-reserved-sold`，不会因为退款释放已售名额；任何对旧已付款订单“补回软库存”的Redis操作都是错误。

## 6. API/Console与运维指标约束

- Portal商品列表/详情页面无需显示Redis内部键，不展示技术口号；展示真实价、套餐单位、活动折扣、已开始/未开始/已结束、最多可购买1份、"剩余名额以提交为准"、状态轮询。普通用户不能看内部库存reserved/sold/错误 payload。
- Console管理侧可见`Cache HIT/MISS/DB_FALLBACK/INVALIDATION_FAILED/STAMPEDE_WAIT`进程计数（低基数）、当前活动MySQL total/reserved/sold、Redis gate epoch/softRemaining/状态、最近重建时间/修复失败及Poison/Intent backlog；**缓存命中率来自真实采样计数，不创建假的“99.9%命中率”**。
- 按运营确认动作缓存失效、手工重建、暂停活动/人工Intent终止必须ADMIN权限+审计；OPERATOR/VIEWER只读，PORTAL_USER仅本人Intent和公开活动，不能传Redis键/活动库存/活动epoch来写入。
- 测试热度：warm/cold cache分别测Redis/DB查询数量、p50/p95/p99；高并发20000 HTTP请求**作为实验流量上限目标**实际报告端到端完成请求数、DB受理数量、拒绝/超时与真实QPS，不能把20000并发写在简历当无测试事实。

## 7. 专项测试与故障门槛

- 两Control缓存命中、跨实例管理员更新的代次失效、读回源过程中商品被下架、新sku发布后旧Reader不能污染、新SKU开售下单金额用MySQL而不信旧缓存。
- 404负缓存3s、随机TTL边界、100个随机搜索不能创建无限键、热点Key过期有界单飞、Redis故障时只读查询降级且DB连接池不耗尽。
- Redis Lua 100份候选在2Control下脚本原子、不能负softRemaining；DB拒绝后带epoch/token补偿至多一次，故意DB COMMIT后HTTP丢响应不可补偿。
- REBUILDING过程中20个旧Lua令牌晚到，DB无老epoch受理；Redis重启/FLUSHDB/NOSCRIPT真实恢复；新epoch重建后 `softRemaining<=total-reserved-sold`（允许短期保守少卖），绝不能导致DB超卖。
- 兼容B普通订单、A免费领取与M V11/V12 Rabbit统计：缓存失效或Redis部分故障不能让已受理Intent/付款/额度被错误回滚；所有测试仅自有随机容器，真实失败与未执行留痕。
- 证据建议`docs/progress/evidence/v12-c1-catalog-cache.json`、`v12-c3-redis-gate.json`、`v12-c8-cache-benchmark.json`，包含环境owner、真Redis7/JDK17版本、实际命令/exitCode与命中/回源计数。

**特别提醒**：缓存技术增加的复杂度应当集中在`CatalogCacheService`、`PromotionCandidateGate`、`PromotionRedisRebuilder`等少量清晰服务中，业务支付/配额不依赖Redis作为权威。缓存只是提高吞吐与保护DB，不可将Cache-Aside当分布式事务。Codex唯一执行合同见[分段任务](../progress/v12-c-high-concurrency.md)。
