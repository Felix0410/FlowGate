if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 'STALE_EPOCH' end
if redis.call('GET', KEYS[2]) ~= 'READY' then return 'GATE_NOT_READY' end
if redis.call('GET', KEYS[3]) ~= ARGV[2] then return 'ALREADY_COMPENSATED' end
redis.call('DEL', KEYS[3])
if redis.call('GET', KEYS[4]) == ARGV[2] then redis.call('DEL', KEYS[4]) end
redis.call('INCR', KEYS[5])
return 'COMPENSATED'
