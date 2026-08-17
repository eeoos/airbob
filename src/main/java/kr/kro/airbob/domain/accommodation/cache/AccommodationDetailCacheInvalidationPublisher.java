package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

/**
 * 숙소 상세에 포함되는 데이터가 바뀌면 같은 트랜잭션에 복구용 outbox를 저장하고 로컬 이벤트를 발행
 * 빠른 캐시 삭제는 AFTER_COMMIT listener가, 실패 복구는 Kafka consumer가 담당
 */
@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;
	private final OutboxEventPublisher outboxEventPublisher;

	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(Long accommodationId, AccommodationDetailCacheInvalidationReason reason) {
		outboxEventPublisher.save(
			EventType.CACHE_INVALIDATION_REQUESTED,
			new AccommodationDetailCacheInvalidationRequestedEvent(accommodationId, reason)
		);
		applicationEventPublisher.publishEvent(
			new AccommodationDetailCacheInvalidationEvent(accommodationId, reason));
	}
}
