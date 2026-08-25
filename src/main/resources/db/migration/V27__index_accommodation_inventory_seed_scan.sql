ALTER TABLE accommodation
    ADD INDEX idx_accommodation_inventory_seed_scan (status, id);
