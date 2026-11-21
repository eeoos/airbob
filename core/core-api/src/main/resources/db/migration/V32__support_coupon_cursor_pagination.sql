-- Stop coupon writers and reconcile missing issuance timestamps before applying V32.
-- Audit timestamps are already UTC: preserve their values and never invent replacements.
DELIMITER $$
DROP PROCEDURE IF EXISTS verify_coupon_cursor_dates$$
CREATE PROCEDURE verify_coupon_cursor_dates()
BEGIN
    IF EXISTS (SELECT 1 FROM member_coupon WHERE created_at IS NULL) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Reconcile member_coupon.created_at from trusted issuance evidence before V32';
    END IF;
END$$
DELIMITER ;
CALL verify_coupon_cursor_dates();
DROP PROCEDURE verify_coupon_cursor_dates;

ALTER TABLE member_coupon
    MODIFY COLUMN created_at datetime(6) NOT NULL,
    ADD INDEX idx_member_coupon_member_created_id (member_id, created_at DESC, id DESC);

CREATE INDEX idx_coupon_active_issue_start_id
    ON coupon (is_active, issue_start_at DESC, id DESC);
