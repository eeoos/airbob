package kr.kro.airbob.search.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationIndexingEvents {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AccommodationCreatedEvent(String accommodationUid, String id) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() { return accommodationUid; }
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AccommodationUpdatedEvent(String accommodationUid) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() { return accommodationUid; }
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AccommodationDeletedEvent(String accommodationUid) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() { return accommodationUid; }
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReviewSummaryChangedEvent(String accommodationUid) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() { return accommodationUid; }
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReservationChangedEvent(String accommodationUid) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() { return accommodationUid; }
	}
}
