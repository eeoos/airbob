-- V31__Extend_review_content_and_accommodation_description_column_size.sql
ALTER TABLE review
    MODIFY COLUMN content TEXT;

ALTER TABLE accommodation
    MODIFY COLUMN description TEXT;
