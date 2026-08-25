package kr.kro.airbob.domain.accommodation.cache.messaging.event;

import java.util.Objects;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventDestination;

public record AccommodationDetailCacheInvalidationRequestedV1(
	Long accommodationId,
	AccommodationDetailCacheInvalidationReason reason
) implements IntegrationEvent {

	public static final String TOPIC = IntegrationEventDestination.Topic.ACCOMMODATION_CACHE;
	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		IntegrationEventDestination.ACCOMMODATION_CACHE,
		"ACCOMMODATION",
		"ACCOMMODATION_DETAIL_CACHE_INVALIDATION_REQUESTED",
		"1"
	);

	public AccommodationDetailCacheInvalidationRequestedV1 {
		if (accommodationId == null || accommodationId <= 0) {
			throw new IllegalArgumentException("accommodationId must be positive");
		}
		Objects.requireNonNull(reason, "reason must not be null");
	}

	@Override
	public EventDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public String aggregateId() {
		return accommodationId.toString();
	}
}
