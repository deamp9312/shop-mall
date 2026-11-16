-- REQ-F-005: 장바구니 한 줄에 수량을 원자적으로 더하고 장바구니 TTL을 갱신한다.
-- KEYS[1] 장바구니 hash | ARGV[1] productId, ARGV[2] 추가할 수량, ARGV[3] 상품당 최대 수량,
-- ARGV[4] 최대 상품 종류 수, ARGV[5] TTL(밀리초)
-- 새 수량을 반환한다. 상품당 한도를 넘게 되면 -1, 장바구니가 가득 찼으면 -2를 반환한다.
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
