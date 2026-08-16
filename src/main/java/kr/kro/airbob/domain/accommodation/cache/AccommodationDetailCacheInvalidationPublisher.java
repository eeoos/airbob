package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 숙소 상세에 포함되는 데이터가 바뀌면 트랜잭션 안에서 무효화 이벤트를 발행
 * 실제 캐시 삭제 시점은 AFTER_COMMIT listener가 결정
 */
@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;

	public void publish(Long accommodationId, AccommodationDetailCacheInvalidationReason reason) {
		applicationEventPublisher.publishEvent(
			new AccommodationDetailCacheInvalidationEvent(accommodationId, reason));
	}
}
