-- Stop every pre-V31 coupon writer before applying this migration. Roll forward only.
-- Preserve issued coupons and remaining composite stock; never recreate consumed units.
DELIMITER $$
DROP PROCEDURE IF EXISTS verify_coupon_strategy_cleanup$$
CREATE PROCEDURE verify_coupon_strategy_cleanup()
BEGIN
    IF EXISTS (SELECT 1 FROM coupon WHERE issue_strategy NOT IN
        ('DB', 'SKIP_SURROGATE_RR', 'SKIP_SURROGATE_RC', 'SKIP_COMPOSITE_RR', 'SKIP_COMPOSITE_RC')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unknown coupon issuance strategy before V31';
    END IF;
    IF EXISTS (SELECT 1 FROM coupon WHERE issue_strategy = 'DB'
        AND is_active = TRUE AND issue_end_at > UTC_TIMESTAMP(6)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Close active conditional-UPDATE campaigns before V31';
    END IF;
    IF EXISTS (SELECT 1 FROM coupon c WHERE c.issue_start_at > UTC_TIMESTAMP(6)
        AND c.issued_quantity > 0 AND NOT EXISTS (SELECT 1 FROM member_coupon mc WHERE mc.coupon_id = c.id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reconcile legacy coupon counters before V31';
    END IF;
    IF EXISTS (SELECT 1 FROM coupon_issue_unit_composite u JOIN coupon c ON c.id = u.coupon_id
        WHERE c.issue_strategy NOT IN ('SKIP_COMPOSITE_RR', 'SKIP_COMPOSITE_RC')
           OR c.stock_prepared_at IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reconcile composite stock ownership before V31';
    END IF;
    IF EXISTS (SELECT 1 FROM coupon_issue_unit u JOIN coupon c ON c.id = u.coupon_id
        WHERE c.issue_strategy = 'DB'
           OR (c.issue_strategy IN ('SKIP_COMPOSITE_RR', 'SKIP_COMPOSITE_RC')
               AND NOT EXISTS (SELECT 1 FROM coupon_issue_unit_composite x
                               WHERE x.coupon_id = u.coupon_id AND x.unit_no = u.unit_no))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reconcile mixed coupon stock before V31';
    END IF;
END$$
DELIMITER ;
CALL verify_coupon_strategy_cleanup();
DROP PROCEDURE verify_coupon_strategy_cleanup;

-- Copy only surviving units. Matching rows allow retrying a completed copy before DDL.
INSERT INTO coupon_issue_unit (coupon_id, unit_no)
SELECT c.coupon_id, c.unit_no
FROM coupon_issue_unit_composite c
LEFT JOIN coupon_issue_unit s ON s.coupon_id = c.coupon_id AND s.unit_no = c.unit_no
WHERE s.id IS NULL;

DROP TABLE coupon_issue_unit_composite;
ALTER TABLE coupon DROP COLUMN issue_strategy, DROP COLUMN issued_quantity;
