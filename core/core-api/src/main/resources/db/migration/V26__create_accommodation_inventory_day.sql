CREATE TABLE accommodation_inventory_day (
    accommodation_id BIGINT NOT NULL,
    stay_date DATE NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id BIGINT NULL,
    hold_expires_at DATETIME(6) NULL,
    PRIMARY KEY (accommodation_id, stay_date),
    KEY idx_inventory_day_reservation_owner (reservation_id, accommodation_id),
    KEY idx_inventory_day_free_retention (state, stay_date, accommodation_id),
    CONSTRAINT fk_inventory_day_accommodation
        FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
    CONSTRAINT fk_inventory_day_reservation_owner
        FOREIGN KEY (reservation_id, accommodation_id)
        REFERENCES reservation (id, accommodation_id),
    CONSTRAINT chk_inventory_day_state_owner CHECK (
        (
            state = 'FREE'
            AND reservation_id IS NULL
            AND hold_expires_at IS NULL
        )
        OR
        (
            state = 'HOLD'
            AND reservation_id IS NOT NULL
            AND hold_expires_at IS NOT NULL
        )
        OR
        (
            state = 'OCCUPIED'
            AND reservation_id IS NOT NULL
            AND hold_expires_at IS NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
