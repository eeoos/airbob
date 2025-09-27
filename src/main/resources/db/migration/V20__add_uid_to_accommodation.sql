-- V20__add_uid_to_accommodation.sql

ALTER TABLE accommodation ADD COLUMN accommodation_uid BINARY(16) NOT NULL UNIQUE;
