-- REQ-SE-001 4): 로그인 실패 횟수를 세고 잠금 구간을 한 번에 (재)시작해 키가 TTL 없이 남는 일이 없게 한다.
-- KEYS[1] 실패 카운터 | ARGV[1] TTL(밀리초). 실패 횟수를 반환한다.
local count = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return count
