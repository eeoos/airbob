-- Stop every pre-V30 coupon reader/writer before this cutover, including checkout/payment
-- paths that use or restore coupons. Existing business values use Seoul time (UTC+09:00).
-- Keep audit timestamps, issuance counters, units and ownership unchanged.
-- After V30, deploy only the UTC-aware application; do not restart a V29 binary.
START TRANSACTION;

UPDATE coupon
SET issue_start_at = DATE_SUB(issue_start_at, INTERVAL 9 HOUR),
    issue_end_at = DATE_SUB(issue_end_at, INTERVAL 9 HOUR),
    usable_from = DATE_SUB(usable_from, INTERVAL 9 HOUR),
    usable_until = DATE_SUB(usable_until, INTERVAL 9 HOUR),
    stock_prepared_at = DATE_SUB(stock_prepared_at, INTERVAL 9 HOUR);

UPDATE member_coupon
SET used_at = DATE_SUB(used_at, INTERVAL 9 HOUR)
WHERE used_at IS NOT NULL;

COMMIT;
