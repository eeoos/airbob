package kr.kro.airbob.domain.settlement.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.settlement.dto.HostMonthlyAggregate;
import kr.kro.airbob.domain.settlement.dto.SettlementLineItem;
import kr.kro.airbob.domain.settlement.dto.SettlementStatusSum;
import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

	Optional<Settlement> findByHostIdAndSettlementMonth(Long hostId, LocalDate settlementMonth);

	List<Settlement> findByHostIdAndSettlementMonthBetweenOrderBySettlementMonthAsc(
		Long hostId, LocalDate from, LocalDate to);

	List<Settlement> findBySettlementMonth(LocalDate settlementMonth);

	// 관리자 조회: 월별 전체 / 상태 필터
	List<Settlement> findBySettlementMonthOrderByHostIdAsc(LocalDate settlementMonth);

	List<Settlement> findBySettlementMonthAndStatusOrderByHostIdAsc(
		LocalDate settlementMonth, SettlementStatus status);

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

	// 정산 상세: 특정 호스트의 한 달 매출을 숙소별로 분해
	@Query(value = """
		SELECT t.accommodation_id AS accommodationId,
			t.accommodation_name  AS accommodationName,
			SUM(t.gross)  AS grossAmount,
			SUM(t.refund) AS refundAmount,
			SUM(t.gross) - SUM(t.refund) AS netAmount
		FROM (
			SELECT a.id AS accommodation_id, a.name AS accommodation_name,
				COALESCE(pt.amount, 0) AS gross, 0 AS refund
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			JOIN accommodation a ON a.id = r.accommodation_id
			WHERE a.member_id = :hostId
			  AND pt.transaction_type = 'CONFIRM'
			  AND DATE(pt.created_at) BETWEEN :monthStart AND :monthEnd
			UNION ALL
			SELECT a.id, a.name, 0, COALESCE(pt.cancel_amount, 0)
			FROM payment_transaction pt
			JOIN reservation r ON r.id = pt.reservation_id
			JOIN accommodation a ON a.id = r.accommodation_id
			WHERE a.member_id = :hostId
			  AND pt.transaction_type IN ('CANCEL', 'PARTIAL_CANCEL')
			  AND DATE(COALESCE(pt.canceled_at, pt.created_at)) BETWEEN :monthStart AND :monthEnd
		) t
		GROUP BY t.accommodation_id, t.accommodation_name
		ORDER BY t.accommodation_id
		""", nativeQuery = true)
	List<SettlementLineItem> findLineItems(@Param("hostId") Long hostId,
		@Param("monthStart") LocalDate monthStart, @Param("monthEnd") LocalDate monthEnd);

	// 호스트 요약: status별 payout 합/건수. settlement은 (host, month) 사전집계 테이블이라
	// 호스트당 행이 적고 host_id 인덱스를 타므로 on-the-fly 집계로 충분(별도 통계 테이블 불필요).
	@Query(value = """
		SELECT status AS status,
			COALESCE(SUM(payout_amount), 0) AS payoutSum,
			COUNT(*) AS cnt
		FROM settlement
		WHERE host_id = :hostId
		GROUP BY status
		""", nativeQuery = true)
	List<SettlementStatusSum> aggregateByHostGroupedByStatus(@Param("hostId") Long hostId);
}
