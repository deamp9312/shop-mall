-- REQ-SE-001 4): count a failed login and (re)start the lock window in one step, so the key never lives without TTL.
-- KEYS[1] failure counter | ARGV[1] ttl millis. Returns the failure count.
-- REQ-SE-001 4): 로그인 실패를 세고 잠금 기간을 한 번에 (재)시작해서, 키가 TTL 없이 남는 일이 없게 한다.
-- KEYS[1] 실패 카운터 | ARGV[1] TTL 밀리초. 실패 횟수를 반환한다.
local count = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return count
