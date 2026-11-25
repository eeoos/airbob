package kr.kro.airbob.core.domain.settlement;

/**
 * 정산 상세의 숙소별 매출. 조회 시점의 결제 원장을 현재 숙소 소유자 기준으로 다시 집계한 값이라
 * 저장된 정산 금액과 합계가 다를 수 있다. 원장 값이 없으면 금액은 null이다.
 */
public record SettlementLine(
	Long accommodationId,
	String accommodationName,
	Long grossAmount,
	Long refundAmount,
	Long netAmount) {
}
