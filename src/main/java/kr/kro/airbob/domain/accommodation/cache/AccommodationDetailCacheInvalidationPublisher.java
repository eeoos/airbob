package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;

	public void publish(Long accommodationId, AccommodationDetailCacheInvalidationReason reason) {
		applicationEventPublisher.publishEvent(
			new AccommodationDetailCacheInvalidationEvent(accommodationId, reason));
	}
}
