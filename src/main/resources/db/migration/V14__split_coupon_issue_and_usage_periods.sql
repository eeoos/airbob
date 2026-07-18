-- 발급 가능 기간과 발급 후 사용 가능 기간을 분리한다.
-- 기존 start_date/end_date 값은 양쪽 기간에 복사해 기존 쿠폰의 동작을 보존한다.

ALTER TABLE coupon
  CHANGE COLUMN start_date usable_from datetime(6) NOT NULL,
  CHANGE COLUMN end_date usable_until datetime(6) NOT NULL,
  ADD COLUMN issue_start_at datetime(6) DEFAULT NULL,
  ADD COLUMN issue_end_at datetime(6) DEFAULT NULL;

UPDATE coupon
SET issue_start_at = usable_from,
    issue_end_at = usable_until;

ALTER TABLE coupon
  MODIFY COLUMN issue_start_at datetime(6) NOT NULL,
  MODIFY COLUMN issue_end_at datetime(6) NOT NULL;
