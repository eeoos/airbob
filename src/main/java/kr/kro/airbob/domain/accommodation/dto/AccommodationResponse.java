package kr.kro.airbob.domain.accommodation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import lombok.Builder;

public class AccommodationResponse {

	public record Create(long id) {
	}

	@Builder
	public record MyAccommodationInfo(
		Long id,
		String name,
		String thumbnailUrl,
		AccommodationStatus status,
		String location,
		Integer basePrice,
		ReviewResponse.ReviewSummary reviewSummary,
		LocalDateTime createdAt
	) {
	}

	@Builder
	public record MyAccommodationInfos(
		List<MyAccommodationInfo> accommodations,
		CursorResponse.PageInfo pageInfo
	) {

	}

	@Builder
	public record DetailInfo(
		long id,
		String name,
		String description,
		AccommodationType type,
		Integer basePrice,
		LocalTime checkInTime,
		LocalTime checkOutTime,

		AddressInfo address,
		Coordinate coordinate,

		HostInfo host,

		PolicyInfo policyInfo,

		List<AmenityInfo> amenities,

		List<String> imageUrls,

		ReviewResponse.ReviewSummary reviewSummary,

		List<LocalDate> unavailableDates,
		Boolean isInWishlist
	) {

	}

	@Builder
	public record AddressInfo(
		String country,
		String city,
		String district,
		String street,
		String detail,
		String postalCode,
		String fullAddress
	) {
	}

	@Builder
	public record Coordinate(
		Double latitude,
		Double longitude
	) {
	}

	@Builder
	public record HostInfo(
		Long id,
		String nickname,
		String profileImageUrl
	) {
	}

	@Builder
	public record PolicyInfo(
		Integer maxOccupancy,
		Integer adultOccupancy,
		Integer childOccupancy,
		Integer infantOccupancy,
		Integer petOccupancy
	) {
	}

	public record AccommodationInfos(
		List<DetailInfo> infos,
		Integer count
	) {
	}

	@Builder
	public record AmenityInfo(
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
		List<AmenityInfo> amenities,
		BigDecimal averageRating,
		Boolean isInWishlist
	) {
	}
}
