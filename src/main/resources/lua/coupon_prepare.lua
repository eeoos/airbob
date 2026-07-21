-- KEYS[1] = coupon:{couponId}:meta
-- KEYS[2] = coupon:{couponId}:issued
-- ARGV = stock, issueStartAt(ms), issueEndAt(ms), active(0|1), expiresAt(ms), unlimited(0|1)
-- unlimited=1의 stock=0은 명시적 마커와 함께만 사용하는 변경하지 않는 센티넬이다.
-- 반환: 1 = 준비 완료, 0 = 기존 상태가 있어 거절

if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
  return 0
end

redis.call('HSET', KEYS[1],
  'stock', ARGV[1],
  'issueStartAt', ARGV[2],
  'issueEndAt', ARGV[3],
  'active', ARGV[4],
  'expiresAt', ARGV[5],
  'unlimited', ARGV[6])
redis.call('PEXPIREAT', KEYS[1], ARGV[5])

return 1
