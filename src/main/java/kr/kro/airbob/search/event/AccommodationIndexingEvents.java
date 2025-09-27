package kr.kro.airbob.search.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationIndexingEvents {

	// 숙소 이벤트
	public record AccommodationCreatedEvent(String accommodationUid){}
	public record AccommodationUpdatedEvent(String accommodationUid){}
	public record AccommodationDeletedEvent(String accommodationUid){}

	// 리뷰 이벤트
	public record ReviewSummaryChangedEvent(String accommodationUid) {}

	// 예약 이벤트
	public record ReservationChangedEvent(String accommodationUid){}
}
