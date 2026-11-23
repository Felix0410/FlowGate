if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 'STALE_EPOCH' end
if redis.call('GET', KEYS[2]) ~= 'READY' then return 'CACHE_NOT_READY' end
if redis.call('EXISTS', KEYS[5]) == 1 then return 'USER_BUSY' end
local remaining = tonumber(redis.call('GET', KEYS[3]))
if not remaining then return 'CACHE_NOT_READY' end
if remaining <= 0 then return 'SOFT_SOLD_OUT' end
redis.call('DECR', KEYS[3])
redis.call('PSETEX', KEYS[4], ARGV[3], ARGV[2])
redis.call('PSETEX', KEYS[5], ARGV[3], ARGV[2])
redis.call('SET', KEYS[6], ARGV[4], 'EX', 60)
return 'CANDIDATE_ONLY'
