-- V2__add_daily_revenue_stats.sql
-- 일일 매출 사전집계(배치 OLAP rollup) 테이블.
-- 소스 = payment_transaction 원장(append-only). grain = (stat_date, accommodation_id).
-- gross  : transaction_type='CONFIRM' 의 amount, DATE(created_at) 기준
-- refund : transaction_type IN ('CANCEL','PARTIAL_CANCEL') 의 cancel_amount, DATE(COALESCE(canceled_at, created_at)) 기준(환불 발생일)
-- net    : gross - refund
-- 배치(StatisticsScheduler)가 날짜별로 DELETE 후 INSERT...SELECT 재집계(멱등).

CREATE TABLE daily_revenue_stats (
  stat_date        date    NOT NULL,
  accommodation_id bigint  NOT NULL,
  gross_amount     bigint  NOT NULL DEFAULT 0,
  refund_amount    bigint  NOT NULL DEFAULT 0,
  net_amount       bigint  NOT NULL DEFAULT 0,
  payment_count    int     NOT NULL DEFAULT 0,
  refund_count     int     NOT NULL DEFAULT 0,
  created_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (stat_date, accommodation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
