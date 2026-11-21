CREATE TABLE reservation_checkout_request (
  id bigint NOT NULL AUTO_INCREMENT,
  member_id bigint NOT NULL,
  endpoint varchar(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reservation_id bigint DEFAULT NULL,
  created_at datetime(6) NOT NULL,
  completed_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_reservation_checkout_request_key
    UNIQUE (member_id, endpoint, key_hash),
  CONSTRAINT uk_reservation_checkout_request_reservation
    UNIQUE (reservation_id),
  KEY idx_reservation_checkout_request_created (created_at, id),
  CONSTRAINT fk_reservation_checkout_request_member
    FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_reservation_checkout_request_reservation
    FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
