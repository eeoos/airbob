package kr.kro.airbob.outbox;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

	// 예약 이벤트
	RESERVATION_CONFIRM_REQUESTED("RESERVATION", "reservation-events"), // 예약 확정 요청
	RESERVATION_CONFIRMED("RESERVATION", "reservation-events"), // 예약 확정 완료
	RESERVATION_EXPIRE_REQUESTED("RESERVATION", "reservation-events"), // 예약 만료 요청
	RESERVATION_EXPIRED("RESERVATION", "reservation-events"),   // 예약 만료 완료
	RESERVATION_PENDING("RESERVATION", "reservation-events"), // 예약 보류
	RESERVATION_CANCELLED("RESERVATION", "reservation-events"), // 예약 취소

	// 결제 이벤트
	PAYMENT_CONFIRM_REQUESTED("PAYMENT", "payment-events"), // 결제 승인 요청
	PAYMENT_COMPLETED("PAYMENT", "payment-events"), // 결제 완료
	PAYMENT_CANCELLATION_REQUESTED("PAYMENT", "payment-events"), // 결제 취소 요청
	PAYMENT_FAILED("PAYMENT", "payment-events"),
	PAYMENT_CANCELLATION_FAILED("PAYMENT", "payment-events"),

	// 숙소 색인 이벤트
	ACCOMMODATION_CREATED("ACCOMMODATION", "accommodation-events"),
	ACCOMMODATION_UPDATED("ACCOMMODATION", "accommodation-events"),
	ACCOMMODATION_DELETED("ACCOMMODATION", "accommodation-events"),
	REVIEW_SUMMARY_CHANGED("ACCOMMODATION", "accommodation-events"),
	RESERVATION_CHANGED("ACCOMMODATION", "accommodation-events"),

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
