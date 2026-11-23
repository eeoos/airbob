package kr.kro.airbob.domain.reservation;

import java.util.UUID;

/** 견적 체크아웃 요청. 멱등성 지문은 이 두 값으로 만든다. requestMessage는 null일 수 있다. */
public record ReservationCheckout(
	UUID quoteUid,
	String requestMessage
) {
}
