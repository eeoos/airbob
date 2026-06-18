package kr.kro.airbob.domain.statistics.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.statistics.dto.DailyRevenueRow;
import kr.kro.airbob.domain.statistics.entity.DailyRevenueStats;
import kr.kro.airbob.domain.statistics.entity.DailyRevenueStatsId;
import kr.kro.airbob.domain.statistics.repository.querydsl.DailyRevenueStatsRepositoryCustom;

public interface DailyRevenueStatsRepository
	extends JpaRepository<DailyRevenueStats, DailyRevenueStatsId>, DailyRevenueStatsRepositoryCustom {

	// ===== 배치 재집계 (날짜별 DELETE 후 INSERT...SELECT → 멱등) =====

	@Modifying
	@Query("DELETE FROM DailyRevenueStats d WHERE d.statDate = :d")
	void deleteByStatDate(@Param("d") LocalDate d);

	// payment_transaction 원장에서 해당 일자의 (숙소별) gross/refund 를 집계해 적재.
	// gross  = CONFIRM.amount @ DATE(created_at)
	// refund = (CANCEL|PARTIAL_CANCEL).cancel_amount @ DATE(COALESCE(canceled_at, created_at))
	@Modifying
	@Query(value = """
		INSERT INTO daily_revenue_stats
			(stat_date, accommodation_id, gross_amount, refund_amount, net_amount,
			 payment_count, refund_count, created_at, updated_at)
		SELECT :d, t.accommodation_id,
			SUM(t.gross), SUM(t.refund), SUM(t.gross) - SUM(t.refund),
			SUM(t.gcount), SUM(t.rcount), NOW(6), NOW(6)
		FROM (
			SELECT r.accommodation_id AS accommodation_id,
				COALESCE(pt.amount, 0) AS gross, 0 AS refund, 1 AS gcount, 0 AS rcount
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			WHERE pt.transaction_type = 'CONFIRM'
			  AND DATE(pt.created_at) = :d
			UNION ALL
			SELECT r.accommodation_id,
				0, COALESCE(pt.cancel_amount, 0), 0, 1
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			WHERE pt.transaction_type IN ('CANCEL', 'PARTIAL_CANCEL')
			  AND DATE(COALESCE(pt.canceled_at, pt.created_at)) = :d
		) t
		GROUP BY t.accommodation_id
		""", nativeQuery = true)
	void aggregateForDate(@Param("d") LocalDate d);

	// ===== 읽기 (after): 사전집계 테이블 일자별 롤업은 QueryDSL(DailyRevenueStatsRepositoryCustom) 제공 =====

	// ===== 읽기 (before/naive): 원장에서 직접 집계 (UNION → native 필수, 사용자 성능 비교용 보존) =====

	@Query(value = """
		SELECT t.bucket_date AS statDate,
			SUM(t.gross)  AS grossAmount,
			SUM(t.refund) AS refundAmount,
			SUM(t.gross) - SUM(t.refund) AS netAmount,
			SUM(t.gcount) AS paymentCount,
			SUM(t.rcount) AS refundCount
		FROM (
			SELECT DATE(pt.created_at) AS bucket_date,
				COALESCE(pt.amount, 0) AS gross, 0 AS refund, 1 AS gcount, 0 AS rcount
			FROM payment_transaction pt
			WHERE pt.transaction_type = 'CONFIRM'
			  AND DATE(pt.created_at) BETWEEN :from AND :to
			UNION ALL
			SELECT DATE(COALESCE(pt.canceled_at, pt.created_at)) AS bucket_date,
				0, COALESCE(pt.cancel_amount, 0), 0, 1
			FROM payment_transaction pt
			WHERE pt.transaction_type IN ('CANCEL', 'PARTIAL_CANCEL')
			  AND DATE(COALESCE(pt.canceled_at, pt.created_at)) BETWEEN :from AND :to
		) t
		GROUP BY t.bucket_date
		ORDER BY t.bucket_date
		""", nativeQuery = true)
	List<DailyRevenueRow> findDailyRevenueFromLedgerNaive(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
