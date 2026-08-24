package kr.kro.airbob.domain.accommodation.cache.messaging.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationEvent;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import lombok.RequiredArgsConstructor;

/**
 * 원본 변경 트랜잭션에 durable invalidation event를 기록하고 로컬 after-commit 삭제를 예약한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxAccommodationDetailCacheInvalidationPublisher
	implements AccommodationDetailCacheInvalidationPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;
	private final OutboxWriter outboxWriter;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(Long accommodationId, AccommodationDetailCacheInvalidationReason reason) {
		outboxWriter.append(
			new AccommodationDetailCacheInvalidationRequestedV1(accommodationId, reason));
		applicationEventPublisher.publishEvent(
			new AccommodationDetailCacheInvalidationEvent(accommodationId, reason));
	}
}
