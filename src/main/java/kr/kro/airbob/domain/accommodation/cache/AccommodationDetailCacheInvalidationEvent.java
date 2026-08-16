package kr.kro.airbob.domain.accommodation.cache;

/**
 * 원본 데이터 변경 트랜잭션과 함께 발행되고, 커밋 뒤 숙소 상세 캐시를 제거하기 위한 이벤트
 */
public record AccommodationDetailCacheInvalidationEvent(
	Long accommodationId,
	AccommodationDetailCacheInvalidationReason reason
) {
}
