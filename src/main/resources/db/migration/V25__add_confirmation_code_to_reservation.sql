-- V25__add_confirmation_code_to_reservation.sql
ALTER TABLE reservation
    ADD COLUMN confirmation_code VARCHAR(10) NULL UNIQUE AFTER message;
