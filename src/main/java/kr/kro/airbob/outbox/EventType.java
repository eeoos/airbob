package kr.kro.airbob.outbox;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

	// 예약 이벤트
	RESERVATION_PENDING("RESERVATION", "reservation-pending"),
	RESERVATION_CANCELLED("RESERVATION", "reservation-cancelled"),

	// 결제 이벤트
	PAYMENT_SUCCEEDED("PAYMENT", "payment-succeeded"),
	PAYMENT_CANCELLATION_FAILED("PAYMENT", "payment-cancellation-failed"),

	// 그 외
	UNKNOWN("UNKNOWN", null); // 알 수 없는 타입 처리

	private final String aggregateType;
	private final String topic;

	public static EventType from(String eventType) {
		return Arrays.stream(values())
			.filter(it -> it.name().equalsIgnoreCase(eventType))
			.findFirst()
			.orElse(UNKNOWN);
	}
}
