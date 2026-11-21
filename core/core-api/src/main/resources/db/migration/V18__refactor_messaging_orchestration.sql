ALTER TABLE outbox
  ADD COLUMN event_id varchar(36) NOT NULL AFTER id,
  ADD COLUMN destination varchar(255) NOT NULL AFTER event_id,
  ADD COLUMN partition_key varchar(255) NOT NULL AFTER destination,
  ADD COLUMN event_version varchar(30) NOT NULL AFTER event_type,
  ADD COLUMN occurred_at datetime(6) NOT NULL AFTER payload,
  ADD COLUMN deduplication_key varchar(255) DEFAULT NULL AFTER occurred_at,
  ADD CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
  ADD CONSTRAINT uk_outbox_deduplication_key UNIQUE (deduplication_key),
  ADD KEY idx_outbox_cleanup (occurred_at, id);

ALTER TABLE payment_operation
  DROP INDEX idx_payment_operation_recovery,
  DROP INDEX idx_payment_operation_lease,
  ADD COLUMN next_action varchar(30) NOT NULL AFTER status,
  ADD COLUMN dispatch_generation bigint NOT NULL DEFAULT 1 AFTER deduplication_key,
  ADD COLUMN queued_at datetime(6) NOT NULL AFTER next_attempt_at,
  ADD COLUMN review_required_at datetime(6) DEFAULT NULL AFTER lease_expires_at,
  ADD COLUMN cancellation_reason varchar(200) DEFAULT NULL AFTER review_required_at,
  ADD COLUMN manual_reconciliation_pending boolean NOT NULL DEFAULT false AFTER cancellation_reason,
  ADD COLUMN manual_review_count int NOT NULL DEFAULT 0 AFTER manual_reconciliation_pending,
  DROP COLUMN last_enqueued_at,
  ADD KEY idx_payment_operation_retry_due (status, next_attempt_at, id),
  ADD KEY idx_payment_operation_lease_due (status, lease_expires_at, id),
  ADD KEY idx_payment_operation_queued (status, queued_at, id);

CREATE TABLE payment_operation_resolution (
  id bigint NOT NULL AUTO_INCREMENT,
  payment_operation_id bigint NOT NULL,
  actor_member_id bigint NOT NULL,
  resolution_action varchar(30) NOT NULL,
  reason varchar(512) NOT NULL,
  evidence_reference varchar(512) DEFAULT NULL,
  previous_status varchar(30) NOT NULL,
  result_status varchar(30) NOT NULL,
  created_at datetime(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_payment_operation_resolution_audit (payment_operation_id, created_at, id),
  CONSTRAINT fk_payment_operation_resolution_operation
    FOREIGN KEY (payment_operation_id) REFERENCES payment_operation (id),
  CONSTRAINT fk_payment_operation_resolution_actor
    FOREIGN KEY (actor_member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
