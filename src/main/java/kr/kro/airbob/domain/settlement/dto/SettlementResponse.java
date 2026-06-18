package kr.kro.airbob.domain.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;

public class SettlementResponse {

	public record HostSettlement(
		Long settlementId,
		LocalDate settlementMonth,
		long grossAmount,
		long refundAmount,
		long netAmount,
		BigDecimal commissionRate,
		long commissionAmount,
		long payoutAmount,
		SettlementStatus status,
		LocalDateTime settledAt
	) {
		public static HostSettlement from(Settlement s) {
			return new HostSettlement(
				s.getId(),
				s.getSettlementMonth(),
				s.getGrossAmount(),
				s.getRefundAmount(),
				s.getNetAmount(),
				s.getCommissionRate(),
				s.getCommissionAmount(),
				s.getPayoutAmount(),
				s.getStatus(),
				s.getSettledAt()
			);
		}
	}
}
