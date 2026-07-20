-- Redis 키 만료·유실 뒤에도 Lua 캠페인의 준비 이력과 발급 경로를 보존한다.
ALTER TABLE coupon
  ADD COLUMN redis_stock_prepared_at datetime(6) DEFAULT NULL;
