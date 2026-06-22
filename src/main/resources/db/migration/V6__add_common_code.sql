-- V6__add_common_code.sql
-- 공통 코드(순수 공통 코드 테이블). 편의시설/숙소유형은 ENUM을 제거하고 DB가 정합성의 단일 소스가 된다.
--  * common_code_group  = 코드 그룹 정의 (PK = 자연키 group_code)
--  * common_code_detail = 그룹별 상세 코드 (PK = 복합키 (group_code, code))
--  * 원본 테이블(accommodation.type, amenity.name)은 code 문자열만 FK 없이 느슨하게 보관
--  * 정합성은 DB FK 대신 애플리케이션(CommonCodeService 캐시)에서 쓰기 시 검증
--  * UNKNOWN 등 파싱 폴백 센티넬은 카탈로그에서 제외(사용자 노출 대상 아님)

CREATE TABLE common_code_group (
  group_code varchar(50) NOT NULL,
  group_name varchar(100) NOT NULL,
  description varchar(255) DEFAULT NULL,
  is_active tinyint(1) NOT NULL DEFAULT 1,
  created_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE common_code_detail (
  group_code varchar(50) NOT NULL,
  code varchar(50) NOT NULL,
  name varchar(100) NOT NULL,
  description varchar(255) DEFAULT NULL,
  sort_order int NOT NULL DEFAULT 0,
  is_active tinyint(1) NOT NULL DEFAULT 1,
  created_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (group_code, code),
  KEY idx_common_code_detail_lookup (group_code, is_active, sort_order),
  CONSTRAINT fk_common_code_detail_group FOREIGN KEY (group_code) REFERENCES common_code_group (group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 그룹 시드
INSERT INTO common_code_group (group_code, group_name, description, is_active, updated_at) VALUES
  ('AMENITY_TYPE', '편의시설', '숙소 편의시설 종류', 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', '숙소유형', '숙소 유형 분류', 1, CURRENT_TIMESTAMP(6));

-- 편의시설 상세 시드 (AmenityType, UNKNOWN 제외)
INSERT INTO common_code_detail (group_code, code, name, sort_order, is_active, updated_at) VALUES
  ('AMENITY_TYPE', 'WIFI', '무선 인터넷', 1, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'AIR_CONDITIONER', '에어컨', 2, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'HEATING', '난방', 3, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'KITCHEN', '주방', 4, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'WASHER', '세탁기', 5, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'DRYER', '건조기', 6, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'PARKING', '주차 공간', 7, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'TV', 'TV', 8, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'HAIR_DRYER', '헤어드라이어', 9, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'IRON', '다리미', 10, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'SHAMPOO', '샴푸', 11, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'BED_LINENS', '침구류', 12, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'EXTRA_PILLOWS', '추가 베개 및 담요', 13, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'CRIB', '아기 침대', 14, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'HIGH_CHAIR', '아기 식탁의자', 15, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'DISHWASHER', '식기세척기', 16, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'COFFEE_MACHINE', '커피 머신', 17, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'MICROWAVE', '전자레인지', 18, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'REFRIGERATOR', '냉장고', 19, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'ELEVATOR', '엘리베이터', 20, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'POOL', '수영장', 21, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'HOT_TUB', '온수 욕조', 22, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'GYM', '헬스장', 23, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'SMOKE_ALARM', '화재 경보기', 24, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'CARBON_MONOXIDE_ALARM', '일산화탄소 경보기', 25, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'FIRE_EXTINGUISHER', '소화기', 26, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'PETS_ALLOWED', '반려동물 허용', 27, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'OUTDOOR_SPACE', '야외 공간', 28, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'BBQ_GRILL', '바베큐 그릴', 29, 1, CURRENT_TIMESTAMP(6)),
  ('AMENITY_TYPE', 'BALCONY', '발코니', 30, 1, CURRENT_TIMESTAMP(6));

-- 숙소유형 상세 시드 (AccommodationType)
INSERT INTO common_code_detail (group_code, code, name, sort_order, is_active, updated_at) VALUES
  ('ACCOMMODATION_TYPE', 'ENTIRE_PLACE', '전체 숙소', 1, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'PRIVATE_ROOM', '개인실', 2, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'SHARED_ROOM', '다인실', 3, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'HOTEL_ROOM', '호텔 객실', 4, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'HOSTEL', '호스텔', 5, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'VILLA', '빌라', 6, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'GUESTHOUSE', '게스트하우스', 7, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'BNB', 'B&B', 8, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'RESORT', '리조트', 9, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'APARTMENT', '아파트', 10, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'HOUSE', '일반 주택', 11, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'TENT', '텐트', 12, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'BOAT', '보트', 13, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'TREEHOUSE', '트리하우스', 14, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'CAMPER_VAN', '캠핑카', 15, 1, CURRENT_TIMESTAMP(6)),
  ('ACCOMMODATION_TYPE', 'CASTLE', '성 같은 특이한 숙소', 16, 1, CURRENT_TIMESTAMP(6));
