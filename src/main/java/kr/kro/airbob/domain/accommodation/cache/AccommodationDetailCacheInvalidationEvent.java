package kr.kro.airbob.domain.accommodation.cache;

public record AccommodationDetailCacheInvalidationEvent(
	Long accommodationId,
	AccommodationDetailCacheInvalidationReason reason
) {
}
