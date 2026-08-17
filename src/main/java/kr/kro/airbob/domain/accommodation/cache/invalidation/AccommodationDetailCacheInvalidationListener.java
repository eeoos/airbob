package kr.kro.airbob.domain.accommodation.cache.invalidation;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import lombok.RequiredArgsConstructor;

/**
 * 원본 변경이 롤백됐을 때 정상 캐시를 지우지 않도록 커밋 이후에만 무효화
 */
@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationListener {

	private final AccommodationDetailCache accommodationDetailCache;

	@TransactionalEventListener(phase = AFTER_COMMIT)
	public void evict(AccommodationDetailCacheInvalidationEvent event) {
		accommodationDetailCache.evict(event.accommodationId(), event.reason());
	}
}
