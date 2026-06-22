-- V11__add_member_coupon.sql
-- 발급된 쿠폰(1인 1매) 테이블. UNIQUE(member_id, coupon_id) 가 중복 발급의 최후 방어선이다.

CREATE TABLE member_coupon (
  id bigint NOT NULL AUTO_INCREMENT,
  member_id bigint NOT NULL,
  coupon_id bigint NOT NULL,
  used bit(1) NOT NULL DEFAULT b'0',
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_coupon (member_id, coupon_id),
  KEY idx_member_coupon_coupon_id (coupon_id),
  CONSTRAINT fk_member_coupon_member FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_member_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
