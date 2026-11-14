-- REQ-F-007 2): check and decrement the available stock of every order line in one step (all or nothing).
-- KEYS[i] available stock counter of line i | ARGV[i] quantity of line i.
-- Returns the 1-based indexes of the lines whose stock is short; an empty array means every line was decremented.
-- A missing counter counts as 0 (sold out), never as unlimited stock.
-- REQ-F-007 2): 모든 주문 줄의 가용 재고를 한 번에 확인하고 차감한다 (전부 아니면 전무).
-- KEYS[i] i번째 줄의 가용 재고 카운터 | ARGV[i] i번째 줄의 수량.
-- 재고가 부족한 줄의 번호(1부터)를 반환한다. 빈 배열이면 모든 줄을 차감했다는 뜻이다.
-- 카운터가 없으면 무제한 재고가 아니라 0(품절)으로 본다.
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
