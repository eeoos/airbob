package kr.kro.airbob.core.domain.payment;

/** 예약 취소 요청. cancelAmount가 null이면 현재 결제 잔액 전액을 취소한다. */
public record PaymentCancellation(
	String cancelReason,
	Long cancelAmount) {
}
