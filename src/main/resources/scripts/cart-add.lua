-- REQ-F-005: add quantity to one cart line atomically and refresh the cart TTL.
-- KEYS[1] cart hash | ARGV[1] productId, ARGV[2] quantity to add, ARGV[3] max quantity per product,
-- ARGV[4] max product kinds, ARGV[5] ttl millis
-- Returns the new quantity, -1 if the per-product limit would be exceeded, -2 if the cart is full.
local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
if current == 0 and redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) then
    return -2
end
local updated = current + tonumber(ARGV[2])
if updated > tonumber(ARGV[3]) then
    return -1
end
redis.call('HSET', KEYS[1], ARGV[1], updated)
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return updated
