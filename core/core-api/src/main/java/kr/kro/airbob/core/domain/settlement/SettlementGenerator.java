package kr.kro.airbob.core.domain.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.settlement.repository.SettlementRepository;
import kr.kro.airbob.core.domain.settlement.repository.projection.HostMonthlyAggregate;
import kr.kro.airbob.core.support.persistence.history.ChangeType;

/**
 * 한 달 결제 원장을 호스트별로 집계해 PENDING 정산을 만들거나 갱신한다. PAID 정산은 건드리지 않는다.
 * 잠금과 트랜잭션은 호출한 Business가 연다.
 */
@Component
public class SettlementGenerator {

	private static final String SOURCE_BATCH = "BATCH";

	private final SettlementRepository settlementRepository;
	private final SettlementHistoryWriter historyWriter;
	private final BigDecimal commissionRate;

	public SettlementGenerator(SettlementRepository settlementRepository, SettlementHistoryWriter historyWriter,
		@Value("${settlement.commission-rate:0.03}")
		BigDecimal commissionRate) {
		this.settlementRepository = settlementRepository;
		this.historyWriter = historyWriter;
		this.commissionRate = commissionRate;
	}

	/** @return 집계한 호스트 수 */
	public int generate(YearMonth month) {
		LocalDate monthStart = month.atDay(1);
		LocalDate monthEnd = month.atEndOfMonth();

		List<HostMonthlyAggregate> aggregates = settlementRepository.aggregateByHostForMonth(monthStart, monthEnd);
		for (HostMonthlyAggregate aggregate : aggregates) {
			upsertPending(aggregate, monthStart);
		}
		return aggregates.size();
	}

	private void upsertPending(HostMonthlyAggregate aggregate, LocalDate monthStart) {
		long gross = nz(aggregate.getGrossAmount());
		long refund = nz(aggregate.getRefundAmount());

		Optional<Settlement> existing = settlementRepository
			.findByHostIdAndSettlementMonthForUpdate(aggregate.getHostId(), monthStart);

		if (existing.isPresent()) {
			Settlement settlement = existing.get();
			if (settlement.isPaid()) {
				return; // 지급 완료 정산은 재집계로 건드리지 않음(불변)
			}
			settlement.updateAmounts(gross, refund, commissionRate);
			historyWriter.recordSystem(settlement, ChangeType.UPDATE, "월 정산 재집계", SOURCE_BATCH);
		} else {
			Settlement settlement = settlementRepository.save(
				Settlement.createPending(aggregate.getHostId(), monthStart, gross, refund, commissionRate));
			historyWriter.recordSystem(settlement, ChangeType.CREATE, "월 정산 생성", SOURCE_BATCH);
		}
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
