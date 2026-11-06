# 第三方算法参考

FlowGate 的 `flowgate-gateway/src/main/resources/lua/token-bucket.lua` 参考
[java-up-up/hmdp-plus](https://github.com/java-up-up/hmdp-plus) 中
`hmdp-redis-tool-framework/hmdp-redis-rate-limit-framework/src/main/resources/lua/tokenBucket.lua`
的令牌补充数学与 Redis TIME 思路，另参照 `rateLimitSliding.lua` 和
`RedisRateLimitHandler.java` 的拒绝分支思路；未引入其 Maven 模块或 Servlet/同步 Redis 代码。

2026-11-06 在 FlowGate 重新适配为单 Key、固定点整数、有界 TTL、固定整数数组、Reactive Redis。
移除了原券 ID、IP/User 双 Key 扣减与业务错误码，没有实现滑动窗口算法。
上游许可证为 Apache License 2.0，完整文本保留于
[licenses/hmdp-plus-APACHE-2.0.txt](licenses/hmdp-plus-APACHE-2.0.txt)。本次检查的上游工作区未发现 NOTICE 文件。
