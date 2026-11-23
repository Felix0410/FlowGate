local old = tonumber(redis.call('GET', KEYS[1]) or '-1')
if old > tonumber(ARGV[1]) then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
redis.call('SET', KEYS[2], 'CLOSED', 'EX', ARGV[3])
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
return 1
