-- V32__add_unique_constraint_to_member_email.sql

ALTER TABLE member ADD CONSTRAINT uk_member_email UNIQUE (email);
