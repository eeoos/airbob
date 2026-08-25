CREATE TEMPORARY TABLE v25_reservation_zero_data_guard (
    reservation_count BIGINT NOT NULL,
    CONSTRAINT chk_v25_reservation_zero_data CHECK (reservation_count = 0)
);

INSERT INTO v25_reservation_zero_data_guard (reservation_count)
SELECT COUNT(*)
FROM reservation;

DROP TEMPORARY TABLE v25_reservation_zero_data_guard;

ALTER TABLE reservation
    ADD CONSTRAINT uk_reservation_id_accommodation UNIQUE (id, accommodation_id);
