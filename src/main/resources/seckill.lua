local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock' .. voucherId
local orderKey = 'seckill:order' .. voucherId

if (tonumber('get', redis.call(stockKey)) <= 0) then
    -- 库存不足
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    -- 说明该用户已经下过单
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0