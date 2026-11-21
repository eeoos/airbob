package kr.kro.airbob.search.messaging.event;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventDestination;

public record AccommodationSearchRefreshRequestedV1(
	UUID accommodationUid
) implements IntegrationEvent {
	public static final String TOPIC = IntegrationEventDestination.Topic.ACCOMMODATION_INDEX;

	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		IntegrationEventDestination.ACCOMMODATION_INDEX,
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
