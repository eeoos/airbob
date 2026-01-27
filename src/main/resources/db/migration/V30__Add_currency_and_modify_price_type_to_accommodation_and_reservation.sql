-- V30__Add_currency_and_modify_price_type_to_accommodation_and_reservation.sql

ALTER TABLE accommodation
    ADD COLUMN currency VARCHAR(3);

ALTER TABLE accommodation
    MODIFY COLUMN base_price BIGINT;


ALTER TABLE reservation
    ADD COLUMN currency VARCHAR(3) NOT NULL;

ALTER TABLE reservation
    MODIFY COLUMN total_price BIGINT NOT NULL;
