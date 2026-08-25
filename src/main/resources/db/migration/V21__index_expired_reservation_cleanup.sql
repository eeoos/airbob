ALTER TABLE reservation
    ADD INDEX idx_reservation_expiration_cleanup (status, expires_at, id);
