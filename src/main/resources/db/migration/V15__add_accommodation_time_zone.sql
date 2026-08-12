ALTER TABLE accommodation ADD COLUMN time_zone_id VARCHAR(64) NULL;
UPDATE accommodation SET time_zone_id = 'Asia/Seoul' WHERE time_zone_id IS NULL;

ALTER TABLE accommodation_history ADD COLUMN time_zone_id VARCHAR(64) NULL;
UPDATE accommodation_history SET time_zone_id = 'Asia/Seoul' WHERE time_zone_id IS NULL;
