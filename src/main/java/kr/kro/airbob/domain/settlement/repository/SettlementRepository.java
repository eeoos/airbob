package kr.kro.airbob.domain.settlement.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.settlement.dto.HostMonthlyAggregate;
import kr.kro.airbob.domain.settlement.entity.Settlement;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

	Optional<Settlement> findByHostIdAndSettlementMonth(Long hostId, LocalDate settlementMonth);

	List<Settlement> findByHostIdAndSettlementMonthBetweenOrderBySettlementMonthAsc(
		Long hostId, LocalDate from, LocalDate to);

	List<Settlement> findBySettlementMonth(LocalDate settlementMonth);

	// 월별 호스트 매출 집계 (원장 직접): gross=CONFIRM.amount @DATE(created_at),
	// refund=(CANCEL|PARTIAL_CANCEL).cancel_amount @DATE(COALESCE(canceled_at, created_at)).
	// reservation ⨝ accommodation.member_id 로 호스트 귀속. (INSERT 아님, 읽기 집계 — UNION이라 native)
	@Query(value = """
		SELECT t.host_id AS hostId,
			SUM(t.gross)  AS grossAmount,
			SUM(t.refund) AS refundAmount,
			SUM(t.gross) - SUM(t.refund) AS netAmount
		FROM (
			SELECT a.member_id AS host_id, COALESCE(pt.amount, 0) AS gross, 0 AS refund
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			JOIN accommodation a ON a.id = r.accommodation_id
			WHERE pt.transaction_type = 'CONFIRM'
			  AND DATE(pt.created_at) BETWEEN :monthStart AND :monthEnd
			UNION ALL
			SELECT a.member_id, 0, COALESCE(pt.cancel_amount, 0)
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			JOIN accommodation a ON a.id = r.accommodation_id
			WHERE pt.transaction_type IN ('CANCEL', 'PARTIAL_CANCEL')
			  AND DATE(COALESCE(pt.canceled_at, pt.created_at)) BETWEEN :monthStart AND :monthEnd
		) t
		GROUP BY t.host_id
		""", nativeQuery = true)
	List<HostMonthlyAggregate> aggregateByHostForMonth(@Param("monthStart") LocalDate monthStart,
		@Param("monthEnd") LocalDate monthEnd);
}
