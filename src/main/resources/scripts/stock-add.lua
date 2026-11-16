-- REQ-F-007: 여러 가용 재고 카운터를 한 번에 delta만큼 증감한다(해제, 관리자 조정, 보상).
-- KEYS[1] resync epoch | KEYS[i] (i >= 2) 카운터 | ARGV[1] 기대 epoch, '' = 항상 적용 | ARGV[i] KEYS[i]의 delta.
-- 적용하면 1을 반환한다. ARGV[1]을 읽은 뒤 resync가 실행됐으면(이미 DB 값을 기록했으므로) 건너뛰고 0을 반환한다.
-- 첫 INCRBY 전에 모든 카운터를 검사하므로, 정수가 아닌 카운터가 있으면 아무것도 바꾸지 않은 채 스크립트가
-- 실패한다(Lua 오류는 앞서 수행한 쓰기를 되돌리지 않는다).
if ARGV[1] ~= '' and (redis.call('GET', KEYS[1]) or '0') ~= ARGV[1] then
    return 0
end
for i = 2, #KEYS do
    local current = redis.call('GET', KEYS[i])
    if current and not string.match(current, '^-?%d+$') then
        return redis.error_reply('available stock counter is not an integer: ' .. KEYS[i])
    end
end
for i = 2, #KEYS do
    redis.call('INCRBY', KEYS[i], ARGV[i])
end
return 1
