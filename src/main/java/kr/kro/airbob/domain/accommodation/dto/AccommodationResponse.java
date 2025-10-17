package kr.kro.airbob.domain.accommodation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.accommodation.common.AmenityType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

public class AccommodationResponse {

	public record Create(long id) {
	}

	public record AccommodationInfo(
		long id

	) {
	}

	public record AmenityInfoResponse(
		AmenityType type,
		Integer count
	) {
	}

	@Builder
	public record RecentlyViewedAccommodationInfos(
		List<RecentlyViewedAccommodationInfo> accommodations,
		int totalCount
	) {
	}

	@Builder
	public record RecentlyViewedAccommodationInfo(
		LocalDateTime viewedAt,
		Long accommodationId,
		String accommodationName,
		String thumbnailUrl,
		List<AmenityInfoResponse> amenities,
		BigDecimal averageRating,
		Boolean isInWishlist
	) {
	}

}
