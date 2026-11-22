package kr.kro.airbob.domain.accommodation.port;

/** 숙소 예약 가능일 조회. reservation이 구현하므로 accommodation은 예약 기간 정책과 재고에 의존하지 않는다. */
public interface AccommodationAvailabilityReader {

	/** 숙소 시간대 기준 현재 예약 가능 기간과, 그 기간 안에서 지금 예약할 수 없는 날짜 구간. */
	AccommodationAvailability readAvailability(Long accommodationId, String timeZoneId);
}
