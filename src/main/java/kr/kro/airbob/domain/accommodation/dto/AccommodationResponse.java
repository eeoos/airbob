package kr.kro.airbob.domain.accommodation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.geo.dto.Coordinate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationResponse {

	public record Create(long id) {
	}

	public record WishlistAccommodationInfo(
		Long accommodationId,
		String name,
		List<String> accommodationImageUrls,
		List<AmenityInfoResponse> amenities,
		BigDecimal averageRating

	) {
	}

	public record AmenityInfoResponse(
		AmenityType type,
		Integer count
	) {
	}

	@Getter
	@Builder
	@AllArgsConstructor
	public static class AccommodationSearchResponseDto {
		private String name;
		private String thumbnailUrl;
		private Integer pricePerNight;
		private Integer maxOccupancy;
		private List<AccommodationRequest.AmenityInfo> amenityInfos;
		private Double averageRating;
		private Integer reviewCount;
	}

	@Builder
	public record RecentlyViewedAccommodations(
		List<RecentlyViewedAccommodation> accommodations,
		int totalCount
	) {
	}

	@Builder
	public record RecentlyViewedAccommodation(
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
