-- KEYS[1] = coupon:{couponId}:meta
-- KEYS[2] = coupon:{couponId}:issued
-- ARGV[1] = memberId
-- 반환: 0 이상 = 차감 후 잔여 재고
--       무제한 승인은 변경하지 않는 stock 센티넬 0
--       -1 매진, -2 중복, -3 시작 전, -4 종료, -5 미준비, -6 비활성

if redis.call('EXISTS', KEYS[1]) == 0 then
  return -5
end

local metadata = redis.call('HMGET', KEYS[1],
  'stock', 'issueStartAt', 'issueEndAt', 'active', 'expiresAt', 'unlimited')
local stock = tonumber(metadata[1])
local issueStartAt = tonumber(metadata[2])
local issueEndAt = tonumber(metadata[3])
local active = metadata[4]
local expiresAt = tonumber(metadata[5])
local unlimited = metadata[6]

if stock == nil or issueStartAt == nil or issueEndAt == nil or active == false or expiresAt == nil then
  return -5
end

-- unlimited 마커가 없는 기존 메타데이터는 유한 쿠폰으로 해석한다.
if unlimited ~= false and unlimited ~= '0' and unlimited ~= '1' then
  return -5
end

local isUnlimited = unlimited == '1'
if isUnlimited and stock ~= 0 then
  return -5
end

if active ~= '1' then
  return -6
end

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

if now < issueStartAt then
  return -3
end

if now >= issueEndAt then
  return -4
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return -2
end

if not isUnlimited and stock <= 0 then
  return -1
end

redis.call('SADD', KEYS[2], ARGV[1])
redis.call('PEXPIREAT', KEYS[2], expiresAt)
if isUnlimited then
  return stock
end
return redis.call('HINCRBY', KEYS[1], 'stock', -1)
