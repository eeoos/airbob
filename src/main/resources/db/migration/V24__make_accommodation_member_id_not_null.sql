-- V24__make_accommodation_member_id_not_null.sql

ALTER TABLE accommodation
    MODIFY COLUMN member_id BIGINT NOT NULL;
