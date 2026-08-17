package kr.kro.airbob.domain.accommodation.cache.invalidation;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.outbox.EventPayload;

/**
 * 원본 변경과 같은 트랜잭션에 저장되어 빠른 무효화가 실패했을 때 다시 삭제하기 위한 outbox 이벤트
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccommodationDetailCacheInvalidationRequestedEvent(
	Long accommodationId,
	AccommodationDetailCacheInvalidationReason reason
) implements EventPayload {

	public AccommodationDetailCacheInvalidationRequestedEvent {
		if (accommodationId == null || accommodationId <= 0) {
			throw new IllegalArgumentException("accommodationId must be positive");
		}
		Objects.requireNonNull(reason, "reason must not be null");
	}

	@Override
	@JsonIgnore
	public String getId() {
		return accommodationId.toString();
	}
}
