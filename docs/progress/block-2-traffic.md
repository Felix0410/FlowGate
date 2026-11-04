# 功能块 2｜分布式限速、并发保护与热点隔离

> 状态：待开始；须待功能块 1 验收通过。  
> 建议分支：feat/block2-traffic-governance。  
> 本文是本功能块的固定实现任务和进度记录；Codex 不新增算法框架、不重选技术，不提前实现功能块 3。

## 1. 交付的业务闭环

管理员在已有控制面创建和发布服务、路由、调用方的流量策略；Gateway 按生效快照执行分布式速率准入与本机在途并发保护。热点慢 API 被限额时，普通 API 不再被无界挤占。每个拒绝都有可解释的原因与计数指标。覆盖 B06、B07、B08。

**不做**：自适应 AI 算法、复杂滑动窗口（首版只选令牌桶）、Redis 分布式并发租约、Kafka、Redisson、Sentinel、自研完整流控框架、UI、大规模性能压测。高级能力放到确有需求时再评估。

## 2. 固定模块与代码落点

不增加 Maven 业务模块。Control 在现有 fg_policy、fg_policy_binding 之上配置和校验；Gateway 增加如下业务类或同等职责，不建立多层无用接口：

- policy/EffectivePolicyResolver：从已匹配 Route 的 configVersion 和服务/API/Caller 身份中查出有序的限速、并发策略。
- admission/AdmissionGlobalFilter：与 Gateway 路由/身份 Filter 顺序配合，调用准入流程，拒绝时直接响应；不进入下游。
- admission/ReactiveTokenBucketLimiter：使用 Spring Data Redis Reactive 执行 Lua，返回 Allowed、Rejected（含规则和原因）、Unavailable。
- admission/LocalConcurrencyLimiter：基于 Java 并发原子状态或原生 Semaphore 的非阻塞 tryAcquire，管理服务和路由的本机在途许可。
- admission/AdmissionMetrics：调用现有 Micrometer 记录限速/并发拒绝数与执行耗时；Route ID 为低基数标签，不能使用真实 Caller ID 或动态原始 Path。
- lua/token-bucket.lua：从 Plus 令牌桶算法重新适配的独立 Lua。保留令牌数学算法与 Redis TIME 时间来源，去掉原券 ID、IP/User 双扣业务。

Control 只需新增策略参数校验（ratePerSecond、burstCapacity、maxInFlight 等）和作用域绑定检查；不增加管理表或新的微服务。Gateway 仍无 JDBC、Servlet 或同步 Redis。

## 3. 限速规则语义：Codex 必须按此执行

首版只做令牌桶，支持作用域：GLOBAL、SERVICE、ROUTE、CALLER。策略类型 RATE_LIMIT。参数统一为 ratePerSecond（正数）、burstCapacity（正整数且不小于一次请求成本）、cost（首版固定 1），enabled。每条策略配置完整后通过功能块 1 的发布链路生效。

**多维规则统一采用固定的顺序检查：GLOBAL → SERVICE → ROUTE → CALLER。**

1. 每一维在**自己的 Redis Key** 上通过 Lua 完成原子判断、令牌补充及单次扣减；不承诺跨维度全有或全无。
2. 前一维拒绝则不再检查后续维度；先前维度已消耗的令牌表示“该层已接收的尝试量”，这就是首版明确公布的分级计数语义。不得把它宣传为多 Key 原子扣减。
3. 全部维度允许才可进入并发判断与下游；拒绝返回 429，必要时设置合理的 Retry-After（可以先用整秒向上取整估算，无法可靠估算就不返回）。
4. 没有绑定限速策略的公开 API 不执行 Redis 限流，但仍通过已有路由与权限判断；缺失/不完整参数的规则在**发布时**校验拒绝，不在网关热路径中静默修正。

Lua 逻辑固定为以下流程：读取同一个 Key 的 last_ms、tokens → Redis TIME 获取当前毫秒 → 对负时间差钳制为 0 → 按 elapsed*rate 补充且不超过 capacity → 令牌足够则扣 1，返回 1，否则返回 0 → 保存最新状态并刷新有界 TTL。脚本输入只有一个 Key；保证单 Key 原子性，规避 Redis Cluster 跨 Slot 问题。脚本必须使用明确返回类型，与 Spring Reactive Redis 解码类型一致，不复制 Plus 中未确认的 Long/Integer 强转。

Redis Key 模板：fg:rl:<policyId>:<policyRevision>:<scope>:<scopeId>。共享全局预算使用固定 scopeId；Caller 预算按**调用方匿名化稳定 ID**区分，不把 token 或原始 IP 作为 Key。更改当前规则参数时增加 policyRevision，使旧配额不污染新参数；其他无关发布不重置配额。Key 设有界 TTL 并以实测确认过期；不要按每请求新建 Key。

Redis 未响应时首版返回明确的 503（受保护 API 继续拒绝，不能静默 fail-open），相关失败模式在功能块 3 再按 API 风险完善。

## 4. 在途并发准入语义

策略类型 CONCURRENCY，参数 maxInFlight > 0；适用 SERVICE/ROUTE。**首版是每个 Gateway 实例的本地并发上限，不能声称多节点共享总在途容量。**

1. 成功通过速率准入后，先尝试获取服务许可，再获取路由许可；前一步成功、后一步失败时要立即释放第一步。
2. 无许可直接 503（overloaded），不能等待或创建无界请求队列。许可获取必须用非阻塞 tryAcquire，不在 Netty Event Loop 等待锁。
3. 请求正常完成、上游 5xx、网关超时、客户端取消、发生异常时，必须在 Reactor 终止阶段执行释放（如 doFinally），同一请求最多释放一次；防止许可泄漏及负数。
4. 控制面禁用、重启、配置版本变化导致旧版本许可对象尚有请求引用时，不能直接丢失正在使用的引用；允许旧请求使用旧版本许可完成，新的请求按照新规则执行。

若希望表达“热点预算预留”，同时配置服务总在途限制和热点路由更低的上限，并在 Demo 的同一共享资源中重现竞争；不能把“单机限流成功”包装为严格的分布式资源隔离。

## 5. 热点隔离演示

沿用 Demo 快速查询和受控慢请求；为了构造真实竞争，可以在 Demo **仅针对压力演示**增加一个可配置的共享有界执行资源或有界 Tomcat 线程数，保证普通和慢 API 确实共享限制。不要增加真实业务或重写原 Demo 核心接口。

实验顺序固定为：
1. 关治理，对慢接口施加高于其资源承载的短时间负载，同时稳定发送普通查询，记录普通 API 延迟和正常成功量。
2. 在已发布策略中为慢 API 配置路由级限速和小于共享资源预算的在途上限，并保留服务全局预算。
3. 以相同负载做小规模定向对照，观察慢 API 429/503 和普通查询的尾延迟是否改善。无共享资源竞争、实验有误或结果没有改善时必须如实说明，最终正式三组大压测留到项目结束。

本功能块只做**足以证明功能行为**的短实验，不做长时间高压测试。

## 6. 定向测试清单

- Redis 脚本测试：初始容量、连续耗尽、补充、时间差为负、TTL、有界内存、返回类型；同 Key 并发测试。
- 多维语义：同一请求 GLOBAL 通过、ROUTE 拒绝时前层如何计数；CALLER A 超额时不会无条件拒绝 Caller B；无配置通过。
- 多 Gateway 实例：相同 Redis Key 的速率预算共享；说明本机并发限额不是分布式总额。
- 并发许可：正常、异常、响应超时、客户端取消、双层许可部分失败后都能释放；在途数不能负数。
- HTTP：超过限速 429，超过在途数 503；拒绝不产生下游业务调用；未认证仍先执行身份/授权约束。
- 配置：发布新限额后只让新版本/新规则生效，其他策略不受误重置。
- 短对照：同环境同负载观察普通 API 不被热点无限占用；注明环境和结果，不能写未经验证的性能百分比。

只构建测试 Block2 新增/修改模块及直接依赖；不再重复 Block0 的每一条历史用例，最终统一大测试后置。

## 7. 代码复用与中文注释

只参考 Plus 的 tokenBucket.lua、rateLimitSliding.lua 及 RedisRateLimitHandler 算法/错误建模思想；**不得复制旧 Servlet RequestContextHolder、StringRedisTemplate、券 ID、用户 IP 双 Key 脚本**。长 Lua、Java 业务方法按 1、2、3 中文分点，尤其注明层次扣减、非严格多维原子性、许可释放和故障响应。避免多余算法和自适应控制。

## 8. 进度回填

| 项目 | 当前 |
|---|---|
| 前置：Block1 验收 | 待通过 |
| 限速/并发/热点功能 | 未开始 |
| 分支与 Git SHA | 无 |
| Redis Lua 及定向测试 | 未执行 |
| 本机并发取消/异常测试 | 未执行 |
| 多节点速率共享小验证 | 未执行 |
| 短热点对照 | 未执行 |
| ChatGPT + 负责人验收 | 未开始 |

完工后填写本块真实改动、脚本、分支 SHA、测试命令/结果、未完成风险；**只提交功能分支，不直接改 main，不进入 Block3**。

**完成标准**：已发布限速/并发策略真实生效，HTTP 拒绝语义正确，Redis 速率跨节点共享，许可终止释放正确，短热点场景可复现且不虚报收益。