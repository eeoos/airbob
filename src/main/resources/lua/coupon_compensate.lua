-- KEYS[1] = coupon:{couponId}:meta
-- KEYS[2] = coupon:{couponId}:issued
-- ARGV[1] = memberId
-- 반환: 1 = 승인 제거(유한 쿠폰은 재고도 복구), 0 = 이미 보상됐거나 승인되지 않음, -1 = 메타 유실

if redis.call('EXISTS', KEYS[1]) == 0 then
  return -1
end

local unlimited = redis.call('HGET', KEYS[1], 'unlimited')
-- unlimited 마커가 없는 기존 메타데이터는 유한 쿠폰으로 해석한다.
if unlimited ~= false and unlimited ~= '0' and unlimited ~= '1' then
  return -1
end

local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 0 then
  return 0
end

if unlimited == '1' then
  return 1
end

redis.call('HINCRBY', KEYS[1], 'stock', 1)
return 1
