-- V33__add_state_column_to_address_table.sql

ALTER TABLE address
    ADD COLUMN state VARCHAR(255);
