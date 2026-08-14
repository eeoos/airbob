package kr.kro.airbob.domain.accommodation.cache;

/**
 * 어떤 변경이 무효화를 발생시켰는지 낮은 카디널리티 메트릭 태그로 구분한다.
 */
public enum AccommodationDetailCacheInvalidationReason
	implements AccommodationDetailCacheMetricRecorder.TaggedValue {
	ACCOMMODATION,
	IMAGE,
	REVIEW
}
