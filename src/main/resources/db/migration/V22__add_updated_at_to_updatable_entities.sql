-- V22__add_updated_at_to_updatable_entities.sql

ALTER TABLE accommodation ADD COLUMN updated_at DATETIME(6);
UPDATE accommodation SET updated_at = created_at;
ALTER TABLE accommodation MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE accommodation_review_summary ADD COLUMN updated_at DATETIME(6);
UPDATE accommodation_review_summary SET updated_at = created_at;
ALTER TABLE accommodation_review_summary MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE discount_policy ADD COLUMN updated_at DATETIME(6);
UPDATE discount_policy SET updated_at = created_at;
ALTER TABLE discount_policy MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE event ADD COLUMN updated_at DATETIME(6);
UPDATE event SET updated_at = created_at;
ALTER TABLE event MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE member ADD COLUMN updated_at DATETIME(6);
UPDATE member SET updated_at = created_at;
ALTER TABLE member MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE payment ADD COLUMN updated_at DATETIME(6);
UPDATE payment SET updated_at = created_at;
ALTER TABLE payment MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE reservation ADD COLUMN updated_at DATETIME(6);
UPDATE reservation SET updated_at = created_at;
ALTER TABLE reservation MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE review ADD COLUMN updated_at DATETIME(6);
UPDATE review SET updated_at = created_at;
ALTER TABLE review MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE wishlist ADD COLUMN updated_at DATETIME(6);
UPDATE wishlist SET updated_at = created_at;
ALTER TABLE wishlist MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE wishlist_accommodation ADD COLUMN updated_at DATETIME(6);
UPDATE wishlist_accommodation SET updated_at = created_at;
ALTER TABLE wishlist_accommodation MODIFY COLUMN updated_at DATETIME(6) NOT NULL;
