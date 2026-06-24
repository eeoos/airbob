-- V10__rename_discount_policy_to_coupon.sql
-- 할인정책(discount_policy)을 쿠폰(coupon)으로 재구성한다.
--  * 자동 프로모션 개념(promotion_type) 제거 — 이번 범위는 유저 발급 쿠폰만 다룬다
--  * 발급 한도/발급 수(total_quantity/issued_quantity) 도입 — 선착순 발급의 정합성 기준
--  * discount_rate→discount_value(int), max_apply_price→max_discount_amount 로 의미 명확화

RENAME TABLE discount_policy TO coupon;

ALTER TABLE coupon
  CHANGE COLUMN discount_rate discount_value int NOT NULL,
  CHANGE COLUMN max_apply_price max_discount_amount int DEFAULT NULL,
  DROP COLUMN promotion_type,
  ADD COLUMN total_quantity int DEFAULT NULL,
  ADD COLUMN issued_quantity int NOT NULL DEFAULT 0,
  MODIFY COLUMN name varchar(255) NOT NULL,
  MODIFY COLUMN discount_type varchar(50) NOT NULL,
  MODIFY COLUMN start_date datetime(6) NOT NULL,
  MODIFY COLUMN end_date datetime(6) NOT NULL,
  MODIFY COLUMN is_active boolean NOT NULL;
