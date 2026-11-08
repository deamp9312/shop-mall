-- REQ-F-005: replace the quantity of an existing cart line and refresh the cart TTL.
-- KEYS[1] cart hash | ARGV[1] productId, ARGV[2] quantity, ARGV[3] ttl millis
-- Returns 1 when updated, 0 when the product is not in the cart.
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
