-- V30__Add_currency_and_modify_price_type_to_accommodation.sql

ALTER TABLE accommodation
    ADD COLUMN currency VARCHAR(3) NOT NULL;

ALTER TABLE accommodation
    MODIFY COLUMN base_price BIGINT NOT NULL;
