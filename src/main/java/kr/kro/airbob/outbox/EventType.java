package kr.kro.airbob.outbox;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

	// 예약 이벤트
	RESERVATION_CONFIRMED("RESERVATION", "reservation-events"), // 예약 확정 완료
	RESERVATION_EXPIRED("RESERVATION", "reservation-events"),   // 예약 만료 완료
	RESERVATION_PENDING("RESERVATION", "reservation-events"), // 예약 보류
	@Deprecated
	RESERVATION_CANCELLED("RESERVATION", "reservation-events"), // 구 취소 요청 이벤트 호환
	RESERVATION_CANCELLATION_REQUESTED("RESERVATION", "reservation-events"), // 예약 취소 요청
	RESERVATION_CANCELLATION_COMPLETE_REQUESTED("RESERVATION", "reservation-events"), // 예약 취소 완료 반영 요청
	RESERVATION_CANCELLATION_REVERT_REQUESTED("RESERVATION", "reservation-events"), // 예약 취소 실패 보상 요청

	// 결제 이벤트
	PAYMENT_EXECUTION_REQUESTED_V1("PAYMENT_OPERATION", "PAYMENT_OPERATION.events"),
	PAYMENT_CANCELLATION_REQUESTED("PAYMENT", "payment-events"), // 결제 취소 요청
	PAYMENT_CANCELLATION_COMPLETED("PAYMENT", "payment-events"), // 결제 취소 완료
	PAYMENT_CANCELLATION_FAILED("PAYMENT", "payment-events"),

	PG_CANCEL_CALL_REQUESTED("PAYMENT", "payment-events"),   // PG 취소 API 호출 요청
	PG_CANCEL_CALL_SUCCEEDED("PAYMENT", "payment-events"),   // PG 취소 API 호출 성공
	PG_CANCEL_CALL_FAILED("PAYMENT", "payment-events"),      // PG 취소 API 호출 실패

	// 숙소 색인 이벤트
	ACCOMMODATION_UPDATED("ACCOMMODATION", "accommodation-events"),
	ACCOMMODATION_DELETED("ACCOMMODATION", "accommodation-events"),
	REVIEW_SUMMARY_CHANGED("ACCOMMODATION", "accommodation-events"),
	RESERVATION_CHANGED("ACCOMMODATION", "accommodation-events"),

	// 숙소 상세 캐시 무효화 이벤트
	CACHE_INVALIDATION_REQUESTED("ACCOMMODATION_CACHE", "ACCOMMODATION_CACHE.events"),

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
