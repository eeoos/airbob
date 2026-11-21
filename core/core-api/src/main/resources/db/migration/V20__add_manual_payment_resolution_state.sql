ALTER TABLE payment_operation
  ADD COLUMN not_paid_resolution_eligible boolean NOT NULL DEFAULT false
    AFTER manual_reconciliation_pending;

ALTER TABLE payment_operation_resolution
  ADD COLUMN dispatch_generation bigint NOT NULL DEFAULT 1 AFTER payment_operation_id,
  ADD CONSTRAINT chk_payment_operation_resolution_generation
    CHECK (dispatch_generation > 0);
