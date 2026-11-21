-- Stop old coupon writers before applying this migration. Redis preparation timestamps
-- cannot be reused as proof that MySQL units exist. Archive/reconcile those campaigns first.
DELIMITER $$
DROP PROCEDURE IF EXISTS verify_coupon_unit_cutover$$
CREATE PROCEDURE verify_coupon_unit_cutover()
BEGIN
    IF EXISTS (SELECT 1 FROM coupon WHERE redis_stock_prepared_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Reconcile and archive Redis coupon preparation before V29';
    END IF;
END$$
DELIMITER ;
CALL verify_coupon_unit_cutover();
DROP PROCEDURE verify_coupon_unit_cutover;

ALTER TABLE coupon
    RENAME COLUMN redis_stock_prepared_at TO stock_prepared_at;
ALTER TABLE coupon
    ADD COLUMN issue_strategy varchar(32) NOT NULL DEFAULT 'SKIP_SURROGATE_RR';

CREATE TABLE coupon_issue_unit (
    id bigint NOT NULL AUTO_INCREMENT,
    coupon_id bigint NOT NULL,
    unit_no int NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_issue_unit_coupon_unit (coupon_id, unit_no),
    CONSTRAINT fk_coupon_issue_unit_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id)
) ENGINE=InnoDB;

CREATE TABLE coupon_issue_unit_composite (
    coupon_id bigint NOT NULL,
    unit_no int NOT NULL,
    PRIMARY KEY (coupon_id, unit_no),
    CONSTRAINT fk_coupon_issue_unit_composite_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id)
) ENGINE=InnoDB;
