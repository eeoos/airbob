-- V28__Add_virtual_account_to_payment_attempt.sql

ALTER TABLE payment_attempt
    ADD COLUMN virtual_bank_code VARCHAR(20) NULL,
    ADD COLUMN virtual_account_number VARCHAR(30) NULL,
    ADD COLUMN virtual_customer_name VARCHAR(100) NULL,
    ADD COLUMN virtual_due_date DATETIME(6) NULL;
