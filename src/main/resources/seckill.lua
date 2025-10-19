-- 先查询库存是否大于0
local seckillKey = 'seckill:stock:' .. ARGV[1]
local orderKey = 'seckill:order:' .. ARGV[1]
local stock = redis.call('get', seckillKey)
if tonumber(stock) <= 0 then
    return 1
end
-- 判断用户是否下单
local ifOrder = redis.call('sismember', orderKey, ARGV[2])
if tonumber(ifOrder) == 1 then
    return 2
end
-- 减库存
redis.call('incrby', seckillKey, -1)
-- 增加订单
redis.call('sadd', orderKey, ARGV[2])
-- 发送订单到消息队列
redis.call('xadd', 'stream.orders', '*', 'voucherId', ARGV[1], 'userId', ARGV[2], 'orderId', ARGV[3])
return 0