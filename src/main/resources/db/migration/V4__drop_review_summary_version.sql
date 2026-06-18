-- V4__drop_review_summary_version.sql
-- accommodation_review_summary 갱신을 낙관적 락(@Version)에서 원자적 upsert(INSERT ... ON DUPLICATE KEY UPDATE)로 전환.
-- 더 이상 version 컬럼이 필요 없어 제거한다.
--  * 동시 리뷰 작성 시 OptimisticLockingFailureException 미발생
--  * 첫 리뷰 동시 작성 시 PK 중복(DataIntegrityViolation) 대신 upsert로 병합

ALTER TABLE accommodation_review_summary DROP COLUMN version;
