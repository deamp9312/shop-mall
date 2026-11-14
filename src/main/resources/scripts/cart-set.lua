-- REQ-F-005: replace the quantity of an existing cart line and refresh the cart TTL.
-- KEYS[1] cart hash | ARGV[1] productId, ARGV[2] quantity, ARGV[3] ttl millis
-- Returns 1 when updated, 0 when the product is not in the cart.
-- REQ-F-005: 장바구니에 있는 줄의 수량을 바꾸고 장바구니 TTL을 갱신한다.
-- KEYS[1] 장바구니 hash | ARGV[1] productId, ARGV[2] 수량, ARGV[3] TTL 밀리초
-- 바꿨으면 1, 상품이 장바구니에 없으면 0을 반환한다.
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
