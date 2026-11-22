package kr.kro.airbob.domain.accommodation.port;

import java.time.Instant;

/** 숙소 수정에 필요한 예약 정보. reservation의 ReservationReader가 구현하므로 accommodation은 reservation에 의존하지 않는다. */
public interface AccommodationReservationReader {

	/** 숙소에 체크아웃 전이면서 재고를 점유한 예약이 있는지. 잠그지 않는 조회다. */
	boolean existsFutureInventoryReservation(Long accommodationId, Instant now);
}
