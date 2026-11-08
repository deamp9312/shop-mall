-- REQ-SE-001 4): count a failed login and (re)start the lock window in one step, so the key never lives without TTL.
-- KEYS[1] failure counter | ARGV[1] ttl millis. Returns the failure count.
local count = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return count
