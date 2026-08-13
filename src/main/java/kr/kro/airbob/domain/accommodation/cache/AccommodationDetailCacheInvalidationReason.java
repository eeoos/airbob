package kr.kro.airbob.domain.accommodation.cache;

public enum AccommodationDetailCacheInvalidationReason
	implements AccommodationDetailCacheMetricRecorder.TaggedValue {
	ACCOMMODATION,
	IMAGE,
	REVIEW
}
