package kr.kro.airbob.domain.accommodation.cache;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;

/**
 * Redis에 저장되는 숙소 상세 값
 * FOUND는 snapshot을 포함하고, NOT_FOUND는 snapshot 없이 404 자체를 캐시
 */
public record AccommodationDetailCacheValue(
	Status status,
	AccommodationDetailSnapshot snapshot
) {
	public static AccommodationDetailCacheValue found(AccommodationDetailSnapshot snapshot) {
		return new AccommodationDetailCacheValue(Status.FOUND, snapshot);
	}

	public static AccommodationDetailCacheValue notFound() {
		return new AccommodationDetailCacheValue(Status.NOT_FOUND, null);
	}

	public enum Status {
		FOUND,
		NOT_FOUND
	}
}
