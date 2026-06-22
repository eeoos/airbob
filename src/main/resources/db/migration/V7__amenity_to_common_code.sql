-- V7__amenity_to_common_code.sql
-- 편의시설 카탈로그 단일화. 기존 amenity 테이블(편의시설 코드 목록)이 common_code_detail(AMENITY_TYPE)과
-- 중복이라, amenity 테이블을 제거하고 accommodation_amenity 가 코드 문자열을 직접 보관하도록 전환한다.
--  * accommodation_amenity.amenity_id (FK→amenity.id) → amenity_code varchar (FK 없는 느슨한 코드)
--  * 정합성은 공통 코드(CommonCodeService 캐시)가 쓰기 시 검증 → DB FK 불필요
--  * common_code_detail(AMENITY_TYPE)이 편의시설의 유일한 카탈로그(단일 source of truth)가 됨

-- 1) 코드 컬럼 추가
ALTER TABLE accommodation_amenity
  ADD COLUMN amenity_code varchar(50) DEFAULT NULL AFTER amenity_id;

-- 2) 기존 연결을 amenity.name 으로 백필
UPDATE accommodation_amenity aa
  JOIN amenity a ON aa.amenity_id = a.id
  SET aa.amenity_code = a.name;

-- 3) FK·기존 컬럼 제거 (컬럼 삭제 시 연관 인덱스도 함께 제거됨)
ALTER TABLE accommodation_amenity
  DROP FOREIGN KEY FK_accommodation_amenity_amenity_id;
ALTER TABLE accommodation_amenity
  DROP COLUMN amenity_id;

-- 4) 중복 카탈로그 테이블 제거
DROP TABLE amenity;
