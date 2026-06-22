-- V12__add_coupon_usage_to_reservation.sql
-- 예약 시 쿠폰 사용/할인 반영.
--  * member_coupon 에 사용 시점/사용된 예약 추적 컬럼 추가
--  * reservation 에 적용된 할인액(discount_amount) 추가 (없으면 0)

ALTER TABLE member_coupon
  ADD COLUMN used_at datetime(6) DEFAULT NULL,
  ADD COLUMN reservation_id bigint DEFAULT NULL;

ALTER TABLE reservation
  ADD COLUMN discount_amount bigint NOT NULL DEFAULT 0;
