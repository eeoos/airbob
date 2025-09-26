-- V18__Refactor_reservation_tables.sql

-- 기존 제약 조건 및 테이블 삭제
ALTER TABLE IF EXISTS payment DROP FOREIGN KEY IF EXISTS FK_payment_reservation_id;
DROP TABLE IF EXISTS payment;
DROP TABLE IF EXISTS reserved_dates;
DROP TABLE IF EXISTS reservation;

-- accommodation 테이블에 check_in/out_time 추가
ALTER TABLE accommodation
    ADD COLUMN check_in_time TIME NOT NULL,
    ADD COLUMN check_out_time TIME NOT NULL;

-- reservation 테이블 재생성
CREATE TABLE reservation
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_uid BINARY(16) NOT NULL UNIQUE,
    accommodation_id BIGINT NOT NULL,
    guest_id BIGINT NOT NULL,
    check_in DATETIME NOT NULL,
    check_out DATETIME NOT NULL,
    guest_count INT NOT NULL,
    total_price INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    message VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT FK_reservation_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodation(id),
    CONSTRAINT FK_reservation_member FOREIGN KEY (guest_id) REFERENCES member(id)
) ENGINE=InnoDB;

CREATE INDEX idx_reservation_check ON reservation (accommodation_id, status, check_in, check_out);
CREATE UNIQUE INDEX uk_reservation_uid ON reservation (reservation_uid);

-- payment 테이블 재생성 (성공한 결제만 저장)
CREATE TABLE payment
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_uid BINARY(16) NOT NULL UNIQUE,
    payment_key VARCHAR(200) NOT NULL UNIQUE,
    order_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    method VARCHAR(50) NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reservation_id BIGINT NOT NULL UNIQUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_to_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB;

CREATE INDEX idx_payment_payment_key ON payment (payment_key);
CREATE UNIQUE INDEX uk_payment_uid ON payment (payment_uid);

-- payment_attempt 테이블 생성 (모든 시도 기록)
CREATE TABLE payment_attempt
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_key VARCHAR(200) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    failure_code VARCHAR(100) NULL,
    failure_message VARCHAR(512) NULL,
    reservation_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_attempt_to_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB;

CREATE INDEX idx_payment_attempt_reservation_id ON payment_attempt (reservation_id);
CREATE INDEX idx_payment_attempt_payment_key ON payment_attempt (payment_key);
