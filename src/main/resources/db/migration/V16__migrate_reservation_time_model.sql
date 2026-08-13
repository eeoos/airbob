ALTER TABLE reservation
    CHANGE COLUMN check_in check_in_date DATE NOT NULL,
    CHANGE COLUMN check_out check_out_date DATE NOT NULL,
    ADD COLUMN check_in_at DATETIME(6) NOT NULL AFTER check_out_date,
    ADD COLUMN check_out_at DATETIME(6) NOT NULL AFTER check_in_at,
    ADD COLUMN time_zone_id VARCHAR(64) NOT NULL AFTER check_out_at;

ALTER TABLE reservation_history
    CHANGE COLUMN check_in check_in_date DATE NULL,
    CHANGE COLUMN check_out check_out_date DATE NULL,
    ADD COLUMN check_in_at DATETIME(6) NULL AFTER check_out_date,
    ADD COLUMN check_out_at DATETIME(6) NULL AFTER check_in_at,
    ADD COLUMN time_zone_id VARCHAR(64) NULL AFTER check_out_at;
