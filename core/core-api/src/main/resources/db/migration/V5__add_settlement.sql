-- V5__add_settlement.sql
-- 호스트 정산 도메인. settlement = 현재상태 source of truth(지급되면 불변),
-- settlement_history = INSERT-only 트랜잭션 이력(reservation_history와 동일 패턴, valid_from/to 없음).
--  * 매출 net(=gross-refund) 위에 수수료/지급액 레이어: payout = net - commission
--  * commission_rate 는 정산 시점 율을 스냅샷(이후 율이 바뀌어도 과거 정산 불변)

CREATE TABLE settlement (
  id bigint NOT NULL AUTO_INCREMENT,
  host_id bigint NOT NULL,
  settlement_month date NOT NULL,
  gross_amount bigint NOT NULL DEFAULT 0,
  refund_amount bigint NOT NULL DEFAULT 0,
  net_amount bigint NOT NULL DEFAULT 0,
  commission_rate decimal(5,4) NOT NULL DEFAULT 0,
  commission_amount bigint NOT NULL DEFAULT 0,
  payout_amount bigint NOT NULL DEFAULT 0,
  status varchar(20) NOT NULL,
  settled_at datetime(6) DEFAULT NULL,
  created_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_host_month (host_id, settlement_month),
  KEY fk_settlement_host (host_id),
  CONSTRAINT fk_settlement_host FOREIGN KEY (host_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_history (
  id bigint NOT NULL AUTO_INCREMENT,
  settlement_id bigint NOT NULL,
  host_id bigint DEFAULT NULL,
  settlement_month date DEFAULT NULL,
  gross_amount bigint DEFAULT NULL,
  refund_amount bigint DEFAULT NULL,
  net_amount bigint DEFAULT NULL,
  commission_rate decimal(5,4) DEFAULT NULL,
  commission_amount bigint DEFAULT NULL,
  payout_amount bigint DEFAULT NULL,
  status varchar(20) DEFAULT NULL,
  history_created_at datetime(6) NOT NULL,
  history_created_by bigint DEFAULT NULL,
  change_type varchar(30) NOT NULL,
  change_reason varchar(255) DEFAULT NULL,
  source_system varchar(30) DEFAULT NULL,
  client_ip varchar(45) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_settlement_history_settlement_id (settlement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
