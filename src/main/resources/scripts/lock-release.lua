-- Releases a lock only when it is still held by the caller's token.
-- KEYS[1] lock key | ARGV[1] owner token. Returns 1 when released.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
