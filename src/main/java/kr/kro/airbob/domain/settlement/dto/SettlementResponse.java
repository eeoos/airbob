package kr.kro.airbob.domain.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

	// 정산 상세: 요약 + 숙소별 내역(commission/payout은 호스트 단위 요약에만, 라인은 매출 기여)
	public record SettlementDetail(
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
		LocalDateTime settledAt,
		List<LineItem> items
	) {
		public static SettlementDetail of(Settlement s, List<LineItem> items) {
			return new SettlementDetail(
				s.getId(), s.getHostId(), s.getSettlementMonth(),
				s.getGrossAmount(), s.getRefundAmount(), s.getNetAmount(),
				s.getCommissionRate(), s.getCommissionAmount(), s.getPayoutAmount(),
				s.getStatus(), s.getSettledAt(), items);
		}
	}

	public record LineItem(
		Long accommodationId,
		String accommodationName,
		long grossAmount,
		long refundAmount,
		long netAmount
	) {
		public static LineItem from(SettlementLineItem row) {
			return new LineItem(
				row.getAccommodationId(),
				row.getAccommodationName(),
				nz(row.getGrossAmount()),
				nz(row.getRefundAmount()),
				nz(row.getNetAmount()));
		}

		private static long nz(Long v) {
			return v == null ? 0L : v;
		}
	}

	// 관리자 조회용(호스트 식별 포함)
	public record AdminSettlement(
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
		LocalDateTime settledAt
	) {
		public static AdminSettlement from(Settlement s) {
			return new AdminSettlement(
				s.getId(),
				s.getHostId(),
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
