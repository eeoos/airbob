package kr.kro.airbob.domain.settlement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;

public class SettlementResponse {

	@Schema(name = "SettlementResponseHostSettlement")
	public record HostSettlement(
		@Schema(description = "정산 ID. 상세 조회·지급 처리 경로의 settlementId", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long settlementId,
		@Schema(description = "정산 월의 1일. 월 집계는 UTC 날짜 기준", example = "2027-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate settlementMonth,
		@Schema(description = "해당 월 CONFIRM 원장의 amount 합계. 숙박일이 아닌 원장 생성일 기준", example = "150000", requiredMode = Schema.RequiredMode.REQUIRED)
		long grossAmount,
		@Schema(description = "해당 월 CANCEL·PARTIAL_CANCEL 원장의 cancel_amount 합계. 취소일 우선, 없으면 원장 생성일 기준", example = "20000", requiredMode = Schema.RequiredMode.REQUIRED)
		long refundAmount,
		@Schema(description = "gross_amount - refund_amount. 다른 달 매출의 환불로 음수일 수 있습니다.", example = "130000", requiredMode = Schema.RequiredMode.REQUIRED)
		long netAmount,
		@Schema(description = "정산 시 저장한 수수료율. 0.03은 3%이며 PENDING 재집계 시 현재 설정으로 갱신합니다.", example = "0.03", requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal commissionRate,
		@Schema(description = "net_amount × commission_rate를 정수로 HALF_UP 반올림한 수수료. 음수일 수 있습니다.", example = "3900", requiredMode = Schema.RequiredMode.REQUIRED)
		long commissionAmount,
		@Schema(description = "net_amount - commission_amount. 실제 송금 여부를 증명하는 값은 아니며 음수일 수 있습니다.", example = "126100", requiredMode = Schema.RequiredMode.REQUIRED)
		long payoutAmount,
		@Schema(description = "PENDING: 지급 처리 전, PAID: 지급 완료 상태를 기록함. 외부 송금 결과와는 별개입니다.", example = "PAID", requiredMode = Schema.RequiredMode.REQUIRED)
		SettlementStatus status,
		@Schema(description = "지급 완료 상태를 기록한 서버 UTC 시각(Z). 지급 전에는 null", example = "2027-02-01T04:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant settledAt
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
	@Schema(name = "SettlementResponseSettlementDetail")
	public record SettlementDetail(
		@Schema(description = "정산 ID. 상세 조회·지급 처리 경로의 settlementId", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long settlementId,
		@Schema(description = "정산 대상 회원 ID", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
		Long hostId,
		@Schema(description = "정산 월의 1일. 월 집계는 UTC 날짜 기준", example = "2027-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate settlementMonth,
		@Schema(description = "해당 월 CONFIRM 원장의 amount 합계. 숙박일이 아닌 원장 생성일 기준", example = "150000", requiredMode = Schema.RequiredMode.REQUIRED)
		long grossAmount,
		@Schema(description = "해당 월 CANCEL·PARTIAL_CANCEL 원장의 cancel_amount 합계. 취소일 우선, 없으면 원장 생성일 기준", example = "20000", requiredMode = Schema.RequiredMode.REQUIRED)
		long refundAmount,
		@Schema(description = "gross_amount - refund_amount. 다른 달 매출의 환불로 음수일 수 있습니다.", example = "130000", requiredMode = Schema.RequiredMode.REQUIRED)
		long netAmount,
		@Schema(description = "정산 시 저장한 수수료율. 0.03은 3%이며 PENDING 재집계 시 현재 설정으로 갱신합니다.", example = "0.03", requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal commissionRate,
		@Schema(description = "net_amount × commission_rate를 정수로 HALF_UP 반올림한 수수료. 음수일 수 있습니다.", example = "3900", requiredMode = Schema.RequiredMode.REQUIRED)
		long commissionAmount,
		@Schema(description = "net_amount - commission_amount. 실제 송금 여부를 증명하는 값은 아니며 음수일 수 있습니다.", example = "126100", requiredMode = Schema.RequiredMode.REQUIRED)
		long payoutAmount,
		@Schema(description = "PENDING: 지급 처리 전, PAID: 지급 완료 상태를 기록함. 외부 송금 결과와는 별개입니다.", example = "PAID", requiredMode = Schema.RequiredMode.REQUIRED)
		SettlementStatus status,
		@Schema(description = "지급 완료 상태를 기록한 서버 UTC 시각(Z). 지급 전에는 null", example = "2027-02-01T04:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant settledAt,
		@Schema(description = "현재 원장에서 계산한 숙소별 내역. 숙소 ID 오름차순이며 요약 금액과 달라질 수 있고 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
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

	@Schema(name = "SettlementResponseLineItem")
	public record LineItem(
		@Schema(description = "숙소 ID", example = "201", requiredMode = Schema.RequiredMode.REQUIRED)
		Long accommodationId,
		@Schema(description = "숙소의 현재 이름. 과거 데이터는 null일 수 있습니다.", example = "서울 숙소", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String accommodationName,
		@Schema(description = "해당 월 CONFIRM 원장의 amount 합계. 숙박일이 아닌 원장 생성일 기준", example = "150000", requiredMode = Schema.RequiredMode.REQUIRED)
		long grossAmount,
		@Schema(description = "해당 월 CANCEL·PARTIAL_CANCEL 원장의 cancel_amount 합계. 취소일 우선, 없으면 원장 생성일 기준", example = "20000", requiredMode = Schema.RequiredMode.REQUIRED)
		long refundAmount,
		@Schema(description = "gross_amount - refund_amount. 다른 달 매출의 환불로 음수일 수 있습니다.", example = "130000", requiredMode = Schema.RequiredMode.REQUIRED)
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

	// 호스트 대시보드 요약: 정산 예정/누적 지급 총액과 건수
	@Schema(name = "SettlementResponseHostSummary")
	public record HostSummary(
		@Schema(description = "저장된 PENDING 정산의 payout_amount 합계. 없으면 0, 음수일 수 있습니다.", example = "126100", requiredMode = Schema.RequiredMode.REQUIRED)
		long pendingPayoutTotal,
		@Schema(description = "PENDING 정산 레코드 수. 예약 건수가 아닙니다.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		long pendingCount,
		@Schema(description = "저장된 PAID 정산의 payout_amount 합계. 없으면 0, 음수일 수 있습니다.", example = "97000", requiredMode = Schema.RequiredMode.REQUIRED)
		long paidPayoutTotal,
		@Schema(description = "PAID 정산 레코드 수. 예약 건수가 아닙니다.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		long paidCount
	) {}

	// 관리자 조회용(호스트 식별 포함)
	@Schema(name = "SettlementResponseAdminSettlement")
	public record AdminSettlement(
		@Schema(description = "정산 ID. 상세 조회·지급 처리 경로의 settlementId", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long settlementId,
		@Schema(description = "정산 대상 회원 ID", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
		Long hostId,
		@Schema(description = "정산 월의 1일. 월 집계는 UTC 날짜 기준", example = "2027-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate settlementMonth,
		@Schema(description = "해당 월 CONFIRM 원장의 amount 합계. 숙박일이 아닌 원장 생성일 기준", example = "150000", requiredMode = Schema.RequiredMode.REQUIRED)
		long grossAmount,
		@Schema(description = "해당 월 CANCEL·PARTIAL_CANCEL 원장의 cancel_amount 합계. 취소일 우선, 없으면 원장 생성일 기준", example = "20000", requiredMode = Schema.RequiredMode.REQUIRED)
		long refundAmount,
		@Schema(description = "gross_amount - refund_amount. 다른 달 매출의 환불로 음수일 수 있습니다.", example = "130000", requiredMode = Schema.RequiredMode.REQUIRED)
		long netAmount,
		@Schema(description = "정산 시 저장한 수수료율. 0.03은 3%이며 PENDING 재집계 시 현재 설정으로 갱신합니다.", example = "0.03", requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal commissionRate,
		@Schema(description = "net_amount × commission_rate를 정수로 HALF_UP 반올림한 수수료. 음수일 수 있습니다.", example = "3900", requiredMode = Schema.RequiredMode.REQUIRED)
		long commissionAmount,
		@Schema(description = "net_amount - commission_amount. 실제 송금 여부를 증명하는 값은 아니며 음수일 수 있습니다.", example = "126100", requiredMode = Schema.RequiredMode.REQUIRED)
		long payoutAmount,
		@Schema(description = "PENDING: 지급 처리 전, PAID: 지급 완료 상태를 기록함. 외부 송금 결과와는 별개입니다.", example = "PAID", requiredMode = Schema.RequiredMode.REQUIRED)
		SettlementStatus status,
		@Schema(description = "지급 완료 상태를 기록한 서버 UTC 시각(Z). 지급 전에는 null", example = "2027-02-01T04:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant settledAt
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
