package kr.kro.airbob.domain.statistics.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public class RevenueStatsResponse {

	@Schema(name = "RevenueStatsResponseDailyRevenue", description = "UTC 날짜별 전체 숙소의 사전 집계. 통화 구분·환산 없이 원장 금액을 합산합니다.")
	public record DailyRevenue(
		@Schema(description = "집계 기준 UTC 날짜", example = "2027-01-01")
		LocalDate date,
		@Schema(description = "해당 UTC 날짜에 생성된 CONFIRM 원장 행의 amount 합계. 금액 null은 0으로 계산", example = "150000")
		long grossAmount,
		@Schema(description = "CANCEL·PARTIAL_CANCEL 원장 행의 cancel_amount 합계. canceled_at의 UTC 날짜, 없으면 created_at 기준. 금액 null은 0", example = "20000")
		long refundAmount,
		@Schema(description = "gross_amount - refund_amount. 수수료 차감 전 차액이며 환불만 있으면 음수 가능", example = "130000")
		long netAmount,
		@Schema(description = "집계된 CONFIRM 원장 행 수. 고유 예약·회원 수가 아니며 금액이 0인 행도 포함", example = "2")
		long paymentCount,
		@Schema(description = "집계된 CANCEL·PARTIAL_CANCEL 원장 행 수. 같은 예약의 여러 환불도 각각 계산", example = "1")
		long refundCount
	) {
	}

	@Schema(name = "RevenueStatsResponseDailyRevenues", description = "양 끝 날짜를 포함한 사전 집계 조회 결과. 실시간 원장 조회가 아닙니다.")
	public record DailyRevenues(
		@Schema(description = "요청한 조회 시작일, 포함", example = "2027-01-01")
		LocalDate from,
		@Schema(description = "요청한 조회 종료일, 포함", example = "2027-01-31")
		LocalDate to,
		@Schema(description = "조회 출처. 항상 stats(사전 집계)", allowableValues = "stats", example = "stats")
		String source,
		@Schema(description = "날짜 오름차순. 집계 행이 없는 날은 생략하며 결과가 없으면 빈 배열. 페이지네이션 없음")
		List<DailyRevenue> items
	) {}
}
