-- Releases a lock only when it is still held by the caller's token.
-- KEYS[1] lock key | ARGV[1] owner token. Returns 1 when released.
-- 호출자의 토큰이 아직 락을 잡고 있을 때만 락을 푼다.
-- KEYS[1] 락 키 | ARGV[1] 소유자 토큰. 풀었으면 1을 반환한다.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
