-- 락이 아직 호출자의 토큰으로 잡혀 있을 때만 해제한다.
-- KEYS[1] 락 키 | ARGV[1] 소유자 토큰. 해제하면 1을 반환한다.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
