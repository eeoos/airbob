package kr.kro.airbob.core.domain.settlement;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.settlement.repository.SettlementRepository;
import kr.kro.airbob.core.domain.settlement.repository.projection.SettlementStatusSum;
import lombok.RequiredArgsConstructor;

/** 정산 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class SettlementReader {

	private final SettlementRepository settlementRepository;

	public Settlement read(Long settlementId) {
		return settlementRepository.findById(settlementId).orElseThrow(SettlementNotFoundException::new);
	}

	/** 정산 행을 FOR UPDATE로 잠근다. */
	public Settlement readForUpdate(Long settlementId) {
		return settlementRepository.findByIdForUpdate(settlementId).orElseThrow(SettlementNotFoundException::new);
	}

	/** 월 정산을 호스트 ID 오름차순으로 읽는다. status가 null이면 모든 상태를 읽는다. */
	public List<Settlement> readMonth(LocalDate monthStart, SettlementStatus status) {
		return status == null
			? settlementRepository.findBySettlementMonthOrderByHostIdAsc(monthStart)
			: settlementRepository.findBySettlementMonthAndStatusOrderByHostIdAsc(monthStart, status);
	}

	/** 호스트의 정산을 settlement_month가 [from, to]인 것만 월 오름차순으로 읽는다. */
	public List<Settlement> readHostMonths(Long hostId, LocalDate from, LocalDate to) {
		return settlementRepository.findByHostIdAndSettlementMonthBetweenOrderBySettlementMonthAsc(hostId, from, to);
	}

	/** 정산 월의 숙소별 매출을 결제 원장에서 다시 집계한다. */
	public List<SettlementLine> readLines(Long hostId, YearMonth month) {
		return settlementRepository.findLineItems(hostId, month.atDay(1), month.atEndOfMonth()).stream()
			.map(row -> new SettlementLine(
				row.getAccommodationId(),
				row.getAccommodationName(),
				row.getGrossAmount(),
				row.getRefundAmount(),
				row.getNetAmount()))
			.toList();
	}

	/** 호스트의 저장된 정산 전체를 PENDING·PAID별로 합친다. */
	public HostSettlementTotals readHostTotals(Long hostId) {
		long pendingPayout = 0;
		long pendingCount = 0;
		long paidPayout = 0;
		long paidCount = 0;

		for (SettlementStatusSum row : settlementRepository.aggregateByHostGroupedByStatus(hostId)) {
			SettlementStatus status = SettlementStatus.valueOf(row.getStatus());
			long payout = row.getPayoutSum() == null ? 0L : row.getPayoutSum();
			long count = row.getCnt() == null ? 0L : row.getCnt();
			if (status == SettlementStatus.PENDING) {
				pendingPayout = payout;
				pendingCount = count;
			} else if (status == SettlementStatus.PAID) {
				paidPayout = payout;
				paidCount = count;
			}
		}
		return new HostSettlementTotals(pendingPayout, pendingCount, paidPayout, paidCount);
	}
}
