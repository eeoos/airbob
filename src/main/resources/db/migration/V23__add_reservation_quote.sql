CREATE TABLE reservation_quote (
  id bigint NOT NULL AUTO_INCREMENT,
  quote_uid binary(16) NOT NULL,
  member_id bigint NOT NULL,
  accommodation_id bigint NOT NULL,
  order_name varchar(255) NOT NULL,
  check_in_date date NOT NULL,
  check_out_date date NOT NULL,
  guest_count int NOT NULL,
  coupon_id bigint DEFAULT NULL,
  nightly_price bigint NOT NULL,
  nights bigint NOT NULL,
  subtotal bigint NOT NULL,
  discount_amount bigint NOT NULL,
  amount bigint NOT NULL,
  currency char(3) NOT NULL,
  quoted_at datetime(6) NOT NULL,
  expires_at datetime(6) NOT NULL,
  reservation_id bigint DEFAULT NULL,
  checked_out_at datetime(6) DEFAULT NULL,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_reservation_quote_uid UNIQUE (quote_uid),
  CONSTRAINT uk_reservation_quote_reservation UNIQUE (reservation_id),
  KEY idx_reservation_quote_member_created (member_id, created_at, id),
  KEY idx_reservation_quote_cleanup (created_at, id),
  CONSTRAINT chk_reservation_quote_dates CHECK (check_out_date > check_in_date),
  CONSTRAINT chk_reservation_quote_guests CHECK (guest_count > 0),
  CONSTRAINT chk_reservation_quote_expiry CHECK (expires_at > quoted_at),
  CONSTRAINT chk_reservation_quote_stay_price CHECK (
    nights = DATEDIFF(check_out_date, check_in_date)
    AND subtotal = nightly_price * nights
  ),
  CONSTRAINT chk_reservation_quote_checkout CHECK (
    (reservation_id IS NULL AND checked_out_at IS NULL)
    OR (reservation_id IS NOT NULL AND checked_out_at IS NOT NULL)
  ),
  CONSTRAINT chk_reservation_quote_price CHECK (
    nightly_price >= 0 AND nights > 0 AND subtotal >= 0
    AND discount_amount >= 0 AND discount_amount <= subtotal
    AND amount = subtotal - discount_amount
  ),
  CONSTRAINT fk_reservation_quote_member FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_reservation_quote_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodation (id),
  CONSTRAINT fk_reservation_quote_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
  CONSTRAINT fk_reservation_quote_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
