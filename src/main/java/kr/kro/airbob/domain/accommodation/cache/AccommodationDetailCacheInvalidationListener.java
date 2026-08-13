package kr.kro.airbob.domain.accommodation.cache;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationListener {

	private final AccommodationDetailCache accommodationDetailCache;

	@TransactionalEventListener(phase = AFTER_COMMIT)
	public void evict(AccommodationDetailCacheInvalidationEvent event) {
		accommodationDetailCache.evict(event.accommodationId(), event.reason());
	}
}
