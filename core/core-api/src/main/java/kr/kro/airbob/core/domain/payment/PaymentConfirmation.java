package kr.kro.airbob.core.domain.payment;

import java.util.UUID;

/** 결제 승인 요청. orderId는 예약 UID 문자열이고, amount와 paymentAttemptId는 결제 시도 발급 값과 같아야 한다. */
public record PaymentConfirmation(
	String paymentKey,
	String orderId,
	Integer amount,
	UUID paymentAttemptId
) {
}
