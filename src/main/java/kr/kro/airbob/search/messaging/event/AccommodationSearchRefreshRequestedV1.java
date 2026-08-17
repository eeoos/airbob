package kr.kro.airbob.search.messaging.event;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;

public record AccommodationSearchRefreshRequestedV1(
	UUID accommodationUid
) implements IntegrationEvent {
	public static final String TOPIC = "ACCOMMODATION_INDEX.events";

	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		TOPIC,
		"ACCOMMODATION",
		"ACCOMMODATION_SEARCH_REFRESH_REQUESTED",
		"1"
	);

	public AccommodationSearchRefreshRequestedV1 {
		Objects.requireNonNull(accommodationUid, "accommodationUid must not be null");
	}

	@Override
	public EventDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public String aggregateId() {
		return accommodationUid.toString();
	}
}
