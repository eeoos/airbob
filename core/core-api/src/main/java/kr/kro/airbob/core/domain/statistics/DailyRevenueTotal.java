package kr.kro.airbob.core.domain.statistics;

import java.time.LocalDate;

/**
 * 한 UTC 날짜의 전체 숙소 사전 집계 합계. 통화 구분·환산 없이 원장 금액을 더한다.
 * netAmount는 grossAmount - refundAmount이며 환불만 있으면 음수가 된다.
 */
public record DailyRevenueTotal(
	LocalDate date,
	long grossAmount,
	long refundAmount,
	long netAmount,
	long paymentCount,
	long refundCount) {
}
