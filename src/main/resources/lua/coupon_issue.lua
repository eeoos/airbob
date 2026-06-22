-- 선착순 쿠폰 발급 원자적 처리
-- KEYS[1] = 재고 카운터 키 (coupon:stock:{couponId})
-- KEYS[2] = 발급자 집합 키   (coupon:issued:{couponId})
-- ARGV[1] = memberId
-- 반환: -2 = 이미 발급(중복), -1 = 매진, 0 이상 = 차감 후 남은 재고
-- Redis 는 명령을 단일 스레드로 순차 실행하고 스크립트 전체가 원자적으로 수행되므로
-- '중복 확인 → 재고 확인 → 발급'이 다른 요청과 끼어들지 않고 한 번에 결정된다.
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return -2
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
  return -1
end

redis.call('SADD', KEYS[2], ARGV[1])
return redis.call('DECR', KEYS[1])
