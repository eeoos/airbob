package kr.kro.airbob.domain.accommodation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationDetailProjection;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationResponse {

	public record Create(long id) {
	}

	@Builder
	public record HostAccommodationInfo(
		Long id,
		String name,
		String thumbnailUrl,
		AccommodationStatus status,
		String type,
		AddressResponse.AddressSummaryInfo addressSummary,
		// Integer basePrice,
		// ReviewResponse.ReviewSummary reviewSummary,
		Instant createdAt
	) {
		public static HostAccommodationInfo from(HostAccommodationProjection accommodation) {
			return HostAccommodationInfo.builder()
				.id(accommodation.id())
				.name(accommodation.name())
				.thumbnailUrl(accommodation.thumbnailUrl())
				.status(accommodation.status())
				.type(accommodation.type())
				.addressSummary(new AddressResponse.AddressSummaryInfo(
					accommodation.country(), accommodation.state(), accommodation.city(), accommodation.district()))
				.createdAt(toUtcInstant(accommodation.createdAt()))
				.build();
		}
	}

	@Builder
	public record HostAccommodationInfos(
		List<HostAccommodationInfo> accommodations,
		CursorResponse.PageInfo pageInfo
	) {
		public static HostAccommodationInfos from(List<HostAccommodationInfo> accommodations, CursorResponse.PageInfo pageInfo) {
			return HostAccommodationInfos.builder()
				.accommodations(accommodations)
				.pageInfo(pageInfo)
				.build();
		}
	}

	@Builder
	public record UnavailableDateRange(
		LocalDate startDate,
		LocalDate endDateExclusive
	) {
	}

	@Builder
	public record Availability(
		LocalDate bookingWindowStartInclusive,
		LocalDate bookingWindowEndExclusive,
		List<UnavailableDateRange> unavailableRanges
	) {
	}

	@Builder
	public record DetailInfo(
		long id,
		String name,
		String description,
		String type,
		Long basePrice,
		String currency,
		LocalTime checkInTime,
		LocalTime checkOutTime,
		String timeZoneId,

		Boolean isInWishlist,
		AddressResponse.AddressSummaryInfo addressSummary,
		AddressResponse.Coordinate coordinate,

		MemberResponse.MemberInfo host,

		PolicyResponse.PolicyInfo policy,

		List<AmenityResponse.AmenityInfo> amenities,

		List<ImageResponse.ImageInfo> images,

		ReviewResponse.ReviewSummary reviewSummary

	) {
		public static DetailInfo from(AccommodationDetailSnapshot snapshot, Boolean isInWishlist) {
			return DetailInfo.builder()
				.id(snapshot.id())
				.name(snapshot.name())
				.description(snapshot.description())
				.type(snapshot.type())
				.basePrice(snapshot.basePrice())
				.currency(snapshot.currency())
				.checkInTime(snapshot.checkInTime())
				.checkOutTime(snapshot.checkOutTime())
				.timeZoneId(snapshot.timeZoneId())
				.isInWishlist(isInWishlist)
				.addressSummary(snapshot.addressSummary())
				.coordinate(snapshot.coordinate())
				.host(snapshot.host())
				.policy(snapshot.policy())
				.amenities(snapshot.amenities())
				.images(snapshot.images())
				.reviewSummary(snapshot.reviewSummary())
				.build();
		}

	}

	@Builder
	public record HostDetail(
		long id,
		String name,
		String description,
		String type,
		Long basePrice,
		String currency,
		LocalTime checkInTime,
		LocalTime checkOutTime,
		String timeZoneId,

		AddressResponse.AddressInfo address,

		PolicyResponse.PolicyInfo policy,

		List<AmenityResponse.AmenityInfo> amenities,

		List<ImageResponse.ImageInfo> images
	) {
		public static HostDetail from(HostAccommodationDetailProjection accommodation,
			List<AmenityResponse.AmenityInfo> amenityInfos,
			List<ImageResponse.ImageInfo> imageInfos) {

			return HostDetail.builder()
				.id(accommodation.id())
				.name(accommodation.name())
				.description(accommodation.description())
				.type(accommodation.type())
				.basePrice(accommodation.basePrice())
				.currency(accommodation.currency())
				.checkInTime(accommodation.checkInTime())
				.checkOutTime(accommodation.checkOutTime())
				.timeZoneId(accommodation.timeZoneId())
				.address(new AddressResponse.AddressInfo(accommodation.country(), accommodation.state(), accommodation.city(),
					accommodation.district(), accommodation.street(), accommodation.addressDetail(), accommodation.postalCode()))
				.policy(new PolicyResponse.PolicyInfo(
					accommodation.maxOccupancy(), accommodation.infantOccupancy(), accommodation.petOccupancy()))
				.amenities(amenityInfos)
				.images(imageInfos)
				.build();
		}
	}

	@Builder
	public record RecentlyViewedAccommodationInfos(
		List<RecentlyViewedAccommodationInfo> accommodations,
		int totalCount
	) {
		public static RecentlyViewedAccommodationInfos from(List<RecentlyViewedAccommodationInfo> accommodationInfos) {
			return RecentlyViewedAccommodationInfos.builder()
				.accommodations(accommodationInfos)
				.totalCount(accommodationInfos.size())
				.build();
		}
	}

	@Builder
	public record RecentlyViewedAccommodationInfo(
		Instant viewedAt,
		Long accommodationId,
		String accommodationName,
		String thumbnailUrl,
		AddressResponse.AddressSummaryInfo addressSummary,
		ReviewResponse.ReviewSummary reviewSummary,
		Boolean isInWishlist
	) {
		public static RecentlyViewedAccommodationInfo from(Instant viewedAt, Accommodation accommodation,
			ReviewResponse.ReviewSummary reviewSummary, boolean isInWishlist) {
			return RecentlyViewedAccommodationInfo.builder()
				.viewedAt(viewedAt)
				.accommodationId(accommodation.getId())
				.accommodationName(accommodation.getName())
				.thumbnailUrl(accommodation.getThumbnailUrl())
				.addressSummary(AddressResponse.AddressSummaryInfo.from(accommodation.getAddress()))
				.reviewSummary(reviewSummary)
				.isInWishlist(isInWishlist)
				.build();
		}
	}

	/**
	 * 예약
	 */
	@Builder
	public record AccommodationBasicInfo(
		long id,
		String name,
		String thumbnailUrl
	) {
		public static AccommodationBasicInfo from(Accommodation accommodation) {
			return AccommodationBasicInfo.builder()
				.id(accommodation.getId())
				.name(accommodation.getName())
				.thumbnailUrl(accommodation.getThumbnailUrl())
				.build();
		}
	}

	private static Instant toUtcInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
	}
}
