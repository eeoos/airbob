-- V29__rename_confirmation_code_to_reservation_code.sql
ALTER TABLE reservation
    CHANGE COLUMN confirmation_code reservation_code VARCHAR(10) NULL UNIQUE;
