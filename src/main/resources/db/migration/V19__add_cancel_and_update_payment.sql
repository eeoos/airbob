-- V19__add_cancel_and_update_payment.sql

-- payment 테이블에 status와 balance_amount 컬럼 추가
ALTER TABLE payment
    ADD COLUMN status VARCHAR(50) NOT NULL,
    ADD COLUMN balance_amount BIGINT NOT NULL;

-- 결제 취소 이력을 저장하는 payment_cancel 테이블
CREATE TABLE payment_cancel
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id             BIGINT       NOT NULL,
    cancel_amount          BIGINT       NOT NULL,
    cancel_reason          VARCHAR(200) NULL,
    transaction_key        VARCHAR(64)  NOT NULL UNIQUE,
    canceled_at            DATETIME(6)  NOT NULL,
    created_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_cancel_to_payment FOREIGN KEY (payment_id) REFERENCES payment (id)
) ENGINE=InnoDB;

CREATE INDEX idx_payment_cancel_payment_id ON payment_cancel (payment_id);
