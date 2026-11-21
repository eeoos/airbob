CREATE TABLE payment_operation (
  id bigint NOT NULL AUTO_INCREMENT,
  operation_uid binary(16) NOT NULL,
  reservation_id bigint NOT NULL,
  requester_member_id bigint NOT NULL,
  operation_type varchar(30) NOT NULL,
  status varchar(30) NOT NULL,
  payment_key varchar(200) NOT NULL,
  expected_amount bigint NOT NULL,
  provider_idempotency_key varchar(100) NOT NULL,
  deduplication_key varchar(100) NOT NULL,
  attempt_count int NOT NULL DEFAULT 0,
  next_attempt_at datetime(6) DEFAULT NULL,
  last_enqueued_at datetime(6) NOT NULL,
  lease_owner varchar(100) DEFAULT NULL,
  lease_expires_at datetime(6) DEFAULT NULL,
  failure_code varchar(100) DEFAULT NULL,
  failure_message varchar(512) DEFAULT NULL,
  completed_at datetime(6) DEFAULT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_operation_uid UNIQUE (operation_uid),
  CONSTRAINT uk_payment_operation_provider_key UNIQUE (provider_idempotency_key),
  CONSTRAINT uk_payment_operation_deduplication_key UNIQUE (deduplication_key),
  CONSTRAINT chk_payment_operation_amount CHECK (expected_amount > 0),
  KEY idx_payment_operation_recovery (status, next_attempt_at, last_enqueued_at),
  KEY idx_payment_operation_lease (lease_expires_at),
  CONSTRAINT fk_payment_operation_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT fk_payment_operation_requester FOREIGN KEY (requester_member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE payment_transaction
  ADD COLUMN payment_operation_id bigint DEFAULT NULL,
  ADD CONSTRAINT uk_payment_transaction_operation_id UNIQUE (payment_operation_id),
  ADD CONSTRAINT fk_payment_transaction_operation
    FOREIGN KEY (payment_operation_id) REFERENCES payment_operation (id);

ALTER TABLE member_coupon
  ADD CONSTRAINT uk_member_coupon_reservation_id UNIQUE (reservation_id);
