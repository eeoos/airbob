ALTER TABLE reservation
    MODIFY COLUMN message varchar(255) DEFAULT NULL,
    ADD INDEX idx_reservation_expiration_cleanup (status, expires_at, id);
