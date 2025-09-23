-- V18__Refactor_reservation_tables.sql
ALTER TABLE payment DROP FOREIGN KEY FK_payment_reservation_id;

DROP TABLE IF EXISTS payment;

DROP TABLE IF EXISTS reserved_dates;
DROP TABLE IF EXISTS reservation;

ALTER TABLE accommodation
    ADD COLUMN check_in_time TIME NOT NULL,
    ADD COLUMN check_out_time TIME NOT NULL;

CREATE TABLE reservation
(
    id BIGINT NOT NULL AUTO_INCREMENT,
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

CREATE TABLE payment
(
    payment_id       BINARY(16)   NOT NULL,
    toss_payment_key VARCHAR(255) NOT NULL UNIQUE,
    toss_order_id   VARCHAR(255) NOT NULL,
    total_amount     BIGINT       NOT NULL,
    payment_method   VARCHAR(50)  NULL,
    status           VARCHAR(50)  NULL,
    requested_at     DATETIME(6)  NOT NULL,
    reservation_id   BIGINT       NULL,
    member_id        BIGINT       NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT fk_payment_to_new_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_payment_to_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB;
