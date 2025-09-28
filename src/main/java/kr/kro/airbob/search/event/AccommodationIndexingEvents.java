package kr.kro.airbob.search.event;

import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationIndexingEvents {

	public record AccommodationCreatedEvent(String accommodationUid) implements EventPayload {
		@Override
		public String getId() { return accommodationUid; }
	}

	public record AccommodationUpdatedEvent(String accommodationUid) implements EventPayload {
		@Override
		public String getId() { return accommodationUid; }
	}

	public record AccommodationDeletedEvent(String accommodationUid) implements EventPayload {
		@Override
		public String getId() { return accommodationUid; }
	}

	public record ReviewSummaryChangedEvent(String accommodationUid) implements EventPayload {
		@Override
		public String getId() { return accommodationUid; }
	}

	public record ReservationChangedEvent(String accommodationUid) implements EventPayload {
		@Override
		public String getId() { return accommodationUid; }
	}
}
