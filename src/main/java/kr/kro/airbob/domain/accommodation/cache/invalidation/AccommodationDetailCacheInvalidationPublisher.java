package kr.kro.airbob.domain.accommodation.cache.invalidation;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;

/** 숙소 상세 변경을 로컬 캐시와 durable messaging 경계에 함께 알리는 application port. */
public interface AccommodationDetailCacheInvalidationPublisher {

	void publish(Long accommodationId, AccommodationDetailCacheInvalidationReason reason);
}
