-- REQ-F-007 2): check and decrement the available stock of every order line in one step (all or nothing).
-- KEYS[i] available stock counter of line i | ARGV[i] quantity of line i.
-- Returns the 1-based indexes of the lines whose stock is short; an empty array means every line was decremented.
-- A missing counter counts as 0 (sold out), never as unlimited stock.
local short = {}
for i = 1, #KEYS do
    local available = tonumber(redis.call('GET', KEYS[i]) or '0')
    if available < tonumber(ARGV[i]) then
        short[#short + 1] = i
    end
end
if #short > 0 then
    return short
end
for i = 1, #KEYS do
    redis.call('DECRBY', KEYS[i], ARGV[i])
end
return short
