-- REQ-F-007: move several available-stock counters by a delta in one step (release, admin adjustment, compensation).
-- KEYS[1] resync epoch | KEYS[i] (i >= 2) counter | ARGV[1] expected epoch, '' = apply always | ARGV[i] delta of KEYS[i].
-- Returns 1 when applied, 0 when skipped because a resync ran after ARGV[1] was read (it already wrote the DB value).
-- Every counter is checked before the first INCRBY, so a counter that is not an integer fails the script with
-- nothing changed (a Lua error does not undo earlier writes).
-- REQ-F-007: 여러 가용 재고 카운터를 한 번에 delta만큼 움직인다 (해제, 관리자 조정, 보상).
-- KEYS[1] 재동기화 epoch | KEYS[i] (i >= 2) 카운터 | ARGV[1] 기대하는 epoch, '' = 항상 적용 | ARGV[i] KEYS[i]의 delta.
-- 적용했으면 1, ARGV[1]을 읽은 뒤 재동기화가 실행되어 건너뛰었으면 0을 반환한다 (재동기화가 이미 DB 값을 썼다).
-- 첫 INCRBY 전에 모든 카운터를 검사하므로, 정수가 아닌 카운터가 있으면 아무것도 바꾸지 않고 스크립트가
-- 실패한다 (Lua 오류는 앞서 쓴 값을 되돌리지 않는다).
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
