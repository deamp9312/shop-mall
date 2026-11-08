-- REQ-F-007: move several available-stock counters by a delta in one step (release, admin adjustment, compensation).
-- KEYS[1] resync epoch | KEYS[i] (i >= 2) counter | ARGV[1] expected epoch, '' = apply always | ARGV[i] delta of KEYS[i].
-- Returns 1 when applied, 0 when skipped because a resync ran after ARGV[1] was read (it already wrote the DB value).
-- Every counter is checked before the first INCRBY, so a counter that is not an integer fails the script with
-- nothing changed (a Lua error does not undo earlier writes).
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
