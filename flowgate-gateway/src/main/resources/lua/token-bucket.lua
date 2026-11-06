-- 来源与许可见 THIRD_PARTY_NOTICES.md；2026-11-06 重新适配。
-- 从 Plus 令牌补充数学和 Redis TIME 思路适配；仅一个 Key，无券/IP/User双扣。
-- 1. 固定点整数：1000 单位=一个令牌；参数在发布时限制为1..1000000。
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local values = redis.call('HMGET', KEYS[1], 'tokensMilli', 'lastMs')
local tokens = tonumber(values[1]) or capacity * 1000
local last = tonumber(values[2]) or now
-- 2. 回拨钳制为零；补充量封顶以防极长空闲时乘法精度溢出。
local elapsed = math.min(math.max(0, now - last), math.ceil(capacity * 1000 / rate))
tokens = math.min(capacity * 1000, tokens + elapsed * rate)
local allowed = 0
local retry = 0
if tokens >= 1000 then
    tokens = tokens - 1000
    allowed = 1
else
    retry = math.ceil((1000 - tokens) / rate)
end
-- 3. 拒绝也更新状态及有界 TTL；返回整数数组，明确单 Key 原子语义。
redis.call('HSET', KEYS[1], 'tokensMilli', tokens, 'lastMs', now)
local ttl = math.min(86400, math.max(60, 2 * math.ceil(capacity / rate)))
redis.call('EXPIRE', KEYS[1], ttl)
return {allowed, retry, tokens}
