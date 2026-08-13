package kr.kro.airbob.domain.accommodation.cache;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;

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
