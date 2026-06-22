-- V1__init.sql
-- 통합 baseline: 누적 마이그레이션을 단일 스키마로 압축.
--  * 모든 시간 컬럼 DATETIME(6) 통일
--  * 모든 엔티티 테이블이 생성 시점부터 created_at/updated_at/created_by/updated_by 보유
--    (created_by/updated_by = member.id, BIGINT, FK 없음, NULL 허용 — 배치/시스템 작업은 NULL)
-- 테이블이 FK 선후관계를 위해 외래키 검사를 일시 비활성화

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE accommodation (
  id bigint NOT NULL AUTO_INCREMENT,
  base_price bigint DEFAULT NULL,
  address_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  member_id bigint NOT NULL,
  occupancy_policy_id bigint DEFAULT NULL,
  description text,
  name varchar(255) DEFAULT NULL,
  thumbnail_url varchar(255) DEFAULT NULL,
  type varchar(50) DEFAULT NULL,
  check_in_time time NOT NULL,
  check_out_time time NOT NULL,
  accommodation_uid binary(16) NOT NULL,
  updated_at datetime(6) NOT NULL,
  status varchar(20) NOT NULL,
  currency varchar(3) DEFAULT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY accommodation_uid (accommodation_uid),
  KEY FK_accommodation_address_id (address_id),
  KEY FK_accommodation_member_id (member_id),
  KEY FK_accommodation_occupancy_policy_id (occupancy_policy_id),
  CONSTRAINT FK_accommodation_address_id FOREIGN KEY (address_id) REFERENCES address (id),
  CONSTRAINT FK_accommodation_member_id FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT FK_accommodation_occupancy_policy_id FOREIGN KEY (occupancy_policy_id) REFERENCES occupancy_policy (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE accommodation_amenity (
  id bigint NOT NULL AUTO_INCREMENT,
  accommodation_id bigint DEFAULT NULL,
  amenity_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  count int DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_accommodation_amenity_accommodation_id (accommodation_id),
  KEY FK_accommodation_amenity_amenity_id (amenity_id),
  CONSTRAINT FK_accommodation_amenity_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT FK_accommodation_amenity_amenity_id FOREIGN KEY (amenity_id) REFERENCES amenity (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE accommodation_discount_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  accommodation_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  discount_policy_id bigint DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_accommodation_discount_policy_accommodation_id (accommodation_id),
  KEY FK_accommodation_discount_policy_discount_policy_id (discount_policy_id),
  CONSTRAINT FK_accommodation_discount_policy_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT FK_accommodation_discount_policy_discount_policy_id FOREIGN KEY (discount_policy_id) REFERENCES discount_policy (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE accommodation_image (
  id bigint NOT NULL AUTO_INCREMENT,
  accommodation_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  image_url varchar(255) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_accommodation_image_accommodation_id (accommodation_id),
  CONSTRAINT FK_accommodation_image_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE accommodation_review_summary (
  accommodation_id bigint NOT NULL,
  total_review_count int NOT NULL DEFAULT '0',
  rating_sum bigint NOT NULL DEFAULT '0',
  average_rating decimal(3,2) NOT NULL DEFAULT '0.00',
  created_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  version bigint NOT NULL DEFAULT '0',
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (accommodation_id),
  CONSTRAINT fk_accommodation_review_summary_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodation (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE address (
  id bigint NOT NULL AUTO_INCREMENT,
  latitude double DEFAULT NULL,
  longitude double DEFAULT NULL,
  postal_code varchar(12) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  city varchar(255) DEFAULT NULL,
  country varchar(255) DEFAULT NULL,
  detail varchar(255) DEFAULT NULL,
  district varchar(255) DEFAULT NULL,
  street varchar(255) DEFAULT NULL,
  state varchar(255) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE amenity (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  name varchar(255) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE discount_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  discount_rate double DEFAULT NULL,
  is_active bit(1) DEFAULT NULL,
  max_apply_price int DEFAULT NULL,
  min_payment_price int DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  end_date datetime(6) DEFAULT NULL,
  start_date datetime(6) DEFAULT NULL,
  description varchar(255) DEFAULT NULL,
  name varchar(255) DEFAULT NULL,
  discount_type varchar(50) DEFAULT NULL,
  promotion_type varchar(50) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE event (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(255) NOT NULL,
  max_participants int NOT NULL,
  start_at datetime(6) NOT NULL,
  end_at datetime(6) NOT NULL,
  status varchar(50) NOT NULL,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE event_participant (
  id bigint NOT NULL AUTO_INCREMENT,
  member_id bigint NOT NULL,
  event_id bigint NOT NULL,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY fk_event_participant_event (event_id),
  CONSTRAINT fk_event_participant_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE failed_indexing_events (
  id bigint NOT NULL AUTO_INCREMENT,
  event_type varchar(100) NOT NULL,
  event_data text NOT NULL,
  error_message text,
  status varchar(20) NOT NULL DEFAULT 'FAILED',
  retry_count int NOT NULL DEFAULT '0',
  failed_at datetime(6) NOT NULL,
  next_retry_at datetime(6) NOT NULL,
  last_retry_at datetime(6) DEFAULT NULL,
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE member (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  email varchar(255) DEFAULT NULL,
  nickname varchar(1000) DEFAULT NULL,
  password varchar(255) DEFAULT NULL,
  thumbnail_image_url varchar(255) DEFAULT NULL,
  role varchar(50) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  status varchar(20) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE member_status_history (
  id bigint NOT NULL AUTO_INCREMENT,
  member_id bigint NOT NULL,
  previous_status varchar(20) DEFAULT NULL,
  new_status varchar(20) NOT NULL,
  changed_at datetime(6) NOT NULL,
  changed_by varchar(50) DEFAULT NULL,
  reason varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY fk_member_status_history_to_member (member_id),
  CONSTRAINT fk_member_status_history_to_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE occupancy_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  infant_occupancy int DEFAULT NULL,
  max_occupancy int DEFAULT NULL,
  pet_occupancy int DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE outbox (
  id bigint NOT NULL AUTO_INCREMENT,
  aggregate_type varchar(255) NOT NULL,
  aggregate_id varchar(255) NOT NULL,
  event_type varchar(255) NOT NULL,
  payload text NOT NULL,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE payment (
  id bigint NOT NULL AUTO_INCREMENT,
  payment_uid binary(16) NOT NULL,
  payment_key varchar(200) NOT NULL,
  order_id varchar(64) NOT NULL,
  amount bigint NOT NULL,
  method varchar(50) NOT NULL,
  approved_at datetime(6) NOT NULL,
  created_at datetime(6) NOT NULL,
  reservation_id bigint NOT NULL,
  status varchar(50) NOT NULL,
  balance_amount bigint NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY payment_uid (payment_uid),
  UNIQUE KEY payment_key (payment_key),
  UNIQUE KEY reservation_id (reservation_id),
  CONSTRAINT fk_payment_to_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE payment_attempt (
  id bigint NOT NULL AUTO_INCREMENT,
  payment_key varchar(200) NOT NULL,
  order_id varchar(64) NOT NULL,
  amount bigint NOT NULL,
  method varchar(50) DEFAULT NULL,
  status varchar(50) NOT NULL,
  failure_code varchar(100) DEFAULT NULL,
  failure_message varchar(512) DEFAULT NULL,
  reservation_id bigint NOT NULL,
  created_at datetime(6) NOT NULL,
  virtual_bank_code varchar(20) DEFAULT NULL,
  virtual_account_number varchar(30) DEFAULT NULL,
  virtual_customer_name varchar(100) DEFAULT NULL,
  virtual_due_date datetime(6) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY fk_payment_attempt_to_reservation (reservation_id),
  CONSTRAINT fk_payment_attempt_to_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE payment_cancel (
  id bigint NOT NULL AUTO_INCREMENT,
  payment_id bigint NOT NULL,
  cancel_amount bigint NOT NULL,
  cancel_reason varchar(200) DEFAULT NULL,
  transaction_key varchar(64) NOT NULL,
  canceled_at datetime(6) NOT NULL,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY transaction_key (transaction_key),
  KEY fk_payment_cancel_to_payment (payment_id),
  CONSTRAINT fk_payment_cancel_to_payment FOREIGN KEY (payment_id) REFERENCES payment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE price_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  end_date date DEFAULT NULL,
  price int DEFAULT NULL,
  start_date date DEFAULT NULL,
  accommodation_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  season varchar(255) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_price_policy_accommodation_id (accommodation_id),
  CONSTRAINT FK_price_policy_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE reservation (
  id bigint NOT NULL AUTO_INCREMENT,
  reservation_uid binary(16) NOT NULL,
  accommodation_id bigint NOT NULL,
  guest_id bigint NOT NULL,
  check_in datetime(6) NOT NULL,
  check_out datetime(6) NOT NULL,
  guest_count int NOT NULL,
  total_price bigint NOT NULL,
  status varchar(50) NOT NULL,
  message varchar(500) DEFAULT NULL,
  reservation_code varchar(10) DEFAULT NULL,
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  currency varchar(3) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY reservation_uid (reservation_uid),
  UNIQUE KEY reservation_code (reservation_code),
  KEY FK_reservation_member (guest_id),
  KEY FK_reservation_accommodation (accommodation_id),
  CONSTRAINT FK_reservation_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT FK_reservation_member FOREIGN KEY (guest_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE reservation_status_history (
  id bigint NOT NULL AUTO_INCREMENT,
  reservation_id bigint NOT NULL,
  previous_status varchar(20) DEFAULT NULL,
  new_status varchar(20) NOT NULL,
  changed_at datetime(6) NOT NULL,
  changed_by varchar(50) DEFAULT NULL,
  reason varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY fk_reservation_status_history_to_reservation (reservation_id),
  CONSTRAINT fk_reservation_status_history_to_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE review (
  id bigint NOT NULL AUTO_INCREMENT,
  rating int DEFAULT NULL,
  accommodation_id bigint DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  member_id bigint DEFAULT NULL,
  content text,
  updated_at datetime(6) NOT NULL,
  status varchar(20) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_review_accommodation_id (accommodation_id),
  KEY FK_review_member_id (member_id),
  CONSTRAINT FK_review_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT FK_review_member_id FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE review_image (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  review_id bigint DEFAULT NULL,
  image_url varchar(255) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_review_image_review_id (review_id),
  CONSTRAINT FK_review_image_review_id FOREIGN KEY (review_id) REFERENCES review (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE wishlist (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(255) NOT NULL,
  created_at datetime(6) DEFAULT NULL,
  member_id bigint DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  status varchar(20) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FK_wishlist_member_id (member_id),
  CONSTRAINT FK_wishlist_member_id FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE wishlist_accommodation (
  id bigint NOT NULL AUTO_INCREMENT,
  memo varchar(1024) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  wishlist_id bigint DEFAULT NULL,
  accommodation_id bigint DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wishlist_accommodation_unique (wishlist_id,accommodation_id),
  KEY FK_wishlist_accommodation_accommodation_id (accommodation_id),
  CONSTRAINT FK_wishlist_accommodation_accommodation_id FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT FK_wishlist_accommodation_wishlist_id FOREIGN KEY (wishlist_id) REFERENCES wishlist (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- 편의시설 마스터 데이터 (구 V27)
INSERT INTO amenity (id, name, created_at, updated_at) VALUES
(1, 'WIFI', NOW(6), NOW(6)),
(2, 'AIR_CONDITIONER', NOW(6), NOW(6)),
(3, 'HEATING', NOW(6), NOW(6)),
(4, 'KITCHEN', NOW(6), NOW(6)),
(5, 'WASHER', NOW(6), NOW(6)),
(6, 'DRYER', NOW(6), NOW(6)),
(7, 'PARKING', NOW(6), NOW(6)),
(8, 'TV', NOW(6), NOW(6)),
(9, 'HAIR_DRYER', NOW(6), NOW(6)),
(10, 'IRON', NOW(6), NOW(6)),
(11, 'SHAMPOO', NOW(6), NOW(6)),
(12, 'BED_LINENS', NOW(6), NOW(6)),
(13, 'EXTRA_PILLOWS', NOW(6), NOW(6)),
(14, 'CRIB', NOW(6), NOW(6)),
(15, 'HIGH_CHAIR', NOW(6), NOW(6)),
(16, 'DISHWASHER', NOW(6), NOW(6)),
(17, 'COFFEE_MACHINE', NOW(6), NOW(6)),
(18, 'MICROWAVE', NOW(6), NOW(6)),
(19, 'REFRIGERATOR', NOW(6), NOW(6)),
(20, 'ELEVATOR', NOW(6), NOW(6)),
(21, 'POOL', NOW(6), NOW(6)),
(22, 'HOT_TUB', NOW(6), NOW(6)),
(23, 'GYM', NOW(6), NOW(6)),
(24, 'SMOKE_ALARM', NOW(6), NOW(6)),
(25, 'CARBON_MONOXIDE_ALARM', NOW(6), NOW(6)),
(26, 'FIRE_EXTINGUISHER', NOW(6), NOW(6)),
(27, 'PETS_ALLOWED', NOW(6), NOW(6)),
(28, 'OUTDOOR_SPACE', NOW(6), NOW(6)),
(29, 'BBQ_GRILL', NOW(6), NOW(6)),
(30, 'BALCONY', NOW(6), NOW(6)),
(31, 'UNKNOWN', NOW(6), NOW(6));

-- 데모 이벤트 (구 V10, 등록/수정일시는 마이그레이션 시점)
INSERT INTO event (name, max_participants, start_at, end_at, status, created_at, updated_at)
VALUES ('선착순 1000명 이벤트', 1000, '2025-07-01 00:00:00', '2025-07-10 23:59:59', 'OPEN', NOW(6), NOW(6));
