package kr.kro.airbob.core.domain.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 저장된 정산 한 건의 값. 금액은 정산을 만들거나 재집계한 시점의 값이다. */
public record SettlementSummary(
	Long settlementId,
	Long hostId,
	LocalDate settlementMonth,
	long grossAmount,
	long refundAmount,
	long netAmount,
	BigDecimal commissionRate,
	long commissionAmount,
	long payoutAmount,
	SettlementStatus status,
	Instant settledAt
) {

	public static SettlementSummary from(Settlement settlement) {
		return new SettlementSummary(
			settlement.getId(),
			settlement.getHostId(),
			settlement.getSettlementMonth(),
			settlement.getGrossAmount(),
			settlement.getRefundAmount(),
			settlement.getNetAmount(),
			settlement.getCommissionRate(),
			settlement.getCommissionAmount(),
			settlement.getPayoutAmount(),
			settlement.getStatus(),
			settlement.getSettledAt()
		);
	}
}
