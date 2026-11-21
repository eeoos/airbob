ALTER TABLE payment_operation_resolution
  MODIFY COLUMN actor_member_id bigint DEFAULT NULL,
  MODIFY COLUMN resolution_action varchar(50) NOT NULL,
  ADD COLUMN actor_type varchar(30) DEFAULT NULL AFTER actor_member_id;

UPDATE payment_operation_resolution
SET actor_type = 'ADMIN'
WHERE actor_type IS NULL;

ALTER TABLE payment_operation_resolution
  MODIFY COLUMN actor_type varchar(30) NOT NULL,
  ADD CONSTRAINT chk_payment_operation_resolution_actor_type
    CHECK (actor_type IN ('SYSTEM', 'ADMIN')),
  ADD CONSTRAINT chk_payment_operation_resolution_actor_identity
    CHECK (
      (actor_type = 'SYSTEM' AND actor_member_id IS NULL)
      OR (actor_type = 'ADMIN' AND actor_member_id IS NOT NULL)
    ),
  ADD CONSTRAINT chk_payment_operation_resolution_action
    CHECK (resolution_action IN (
      'RECONCILIATION_REQUESTED',
      'RECONCILIATION_APPLIED',
      'RECONCILIATION_DECLINED',
      'RECONCILIATION_RETURNED_TO_REVIEW',
      'MARKED_NOT_PAID'
    ));

ALTER TABLE payment_operation
  ADD KEY idx_payment_operation_manual_review_queue (status, review_required_at, id);
