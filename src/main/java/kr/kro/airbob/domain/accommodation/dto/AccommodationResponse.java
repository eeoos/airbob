package kr.kro.airbob.domain.accommodation.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
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
		AccommodationType type,
		AddressResponse.AddressSummaryInfo addressInfo,
		// Integer basePrice,
		// ReviewResponse.ReviewSummary reviewSummary,
		LocalDateTime createdAt
	) {
		public static HostAccommodationInfo from(Accommodation accommodation) {
			Address address = accommodation.getAddress();
			return HostAccommodationInfo.builder()
				.id(accommodation.getId())
				.name(accommodation.getName())
				.thumbnailUrl(accommodation.getThumbnailUrl())
				.status(accommodation.getStatus())
				.type(accommodation.getType())
				.addressInfo(AddressResponse.AddressSummaryInfo.from(address))
				.createdAt(accommodation.getCreatedAt())
				.build();
		}
	}

	@Builder
	public record HostAccommodationInfos(
		List<HostAccommodationInfo> accommodationInfos,
		CursorResponse.PageInfo pageInfo
	) {
		public static HostAccommodationInfos from(List<HostAccommodationInfo> accommodationInfos, CursorResponse.PageInfo pageInfo) {
			return HostAccommodationInfos.builder()
				.accommodationInfos(accommodationInfos)
				.pageInfo(pageInfo)
				.build();
		}
	}

	@Builder
	public record DetailInfo(
		long id,
		String name,
		String description,
		AccommodationType type,
		Long basePrice,
		String currency,
		LocalTime checkInTime,
		LocalTime checkOutTime,

		List<LocalDate> unavailableDates,
		Boolean isInWishlist,
		AddressResponse.AddressSummaryInfo addressInfo,
		AddressResponse.Coordinate coordinate,

		MemberResponse.MemberInfo hostInfo,

		PolicyResponse.PolicyInfo policyInfo,

		AmenityResponse.AmenityInfos amenityInfos,

		ImageResponse.ImageInfos imageInfos,

		ReviewResponse.ReviewSummary reviewSummary

	) {
		public static DetailInfo from(Accommodation accommodation, List<LocalDate> unavailableDates,
			Boolean isInWishlist, List<AmenityResponse.AmenityInfo> amenityInfos, List<ImageResponse.ImageInfo> imageInfo,
			ReviewResponse.ReviewSummary reviewSummary) {

			Address address = accommodation.getAddress();
			Member host = accommodation.getMember();
			OccupancyPolicy policy = accommodation.getOccupancyPolicy();
			return DetailInfo.builder()
				.id(accommodation.getId())
				.name(accommodation.getName())
				.description(accommodation.getDescription())
				.type(accommodation.getType())
				.basePrice(accommodation.getBasePrice())
				.currency(accommodation.getCurrency())
				.checkInTime(accommodation.getCheckInTime())
				.checkOutTime(accommodation.getCheckOutTime())
				.unavailableDates(unavailableDates)
				.isInWishlist(isInWishlist)
				.addressInfo(AddressResponse.AddressSummaryInfo.from(address))
				.coordinate(AddressResponse.Coordinate.from(address))
				.hostInfo(MemberResponse.MemberInfo.from(host))
				.policyInfo(PolicyResponse.PolicyInfo.from(policy))
				.amenityInfos(AmenityResponse.AmenityInfos.from(amenityInfos))
				.imageInfos(ImageResponse.ImageInfos.from(imageInfo))
				.reviewSummary(reviewSummary)
				.build();
		}
	}

	@Builder
	public record HostDetail(
		long id,
		String name,
		String description,
		AccommodationType type,
		Long basePrice,
		String currency,
		LocalTime checkInTime,
		LocalTime checkOutTime,

		AddressResponse.AddressInfo addressInfo,
		AddressResponse.Coordinate coordinate,

		MemberResponse.MemberInfo hostInfo,

		PolicyResponse.PolicyInfo policyInfo,

		AmenityResponse.AmenityInfos amenityInfos,

		ImageResponse.ImageInfos imageInfos,

		ReviewResponse.ReviewSummary reviewSummary
	) {
		public static HostDetail from(Accommodation accommodation,
			List<AmenityResponse.AmenityInfo> amenityInfos,
			List<ImageResponse.ImageInfo> imageInfos,
			ReviewResponse.ReviewSummary reviewSummary) {

			Address address = accommodation.getAddress();
			OccupancyPolicy policy = accommodation.getOccupancyPolicy();
			Member host = accommodation.getMember();

			return HostDetail.builder()
				.id(accommodation.getId())
				.name(accommodation.getName())
				.description(accommodation.getDescription())
				.type(accommodation.getType())
				.basePrice(accommodation.getBasePrice())
				.currency(accommodation.getCurrency())
				.checkInTime(accommodation.getCheckInTime())
				.checkOutTime(accommodation.getCheckOutTime())
				.addressInfo(AddressResponse.AddressInfo.from(address))
				.coordinate(AddressResponse.Coordinate.from(address))
				.hostInfo(MemberResponse.MemberInfo.from(host))
				.policyInfo(PolicyResponse.PolicyInfo.from(policy))
				.amenityInfos(AmenityResponse.AmenityInfos.from(amenityInfos))
				.imageInfos(ImageResponse.ImageInfos.from(imageInfos))
				.reviewSummary(reviewSummary)
				.build();
		}
	}

	@Builder
	public record RecentlyViewedAccommodationInfos(
		List<RecentlyViewedAccommodationInfo> accommodationInfos,
		int totalCount
	) {
		public static RecentlyViewedAccommodationInfos from(List<RecentlyViewedAccommodationInfo> accommodationInfos) {
			return RecentlyViewedAccommodationInfos.builder()
				.accommodationInfos(accommodationInfos)
				.totalCount(accommodationInfos.size())
				.build();
		}
	}

	@Builder
	public record RecentlyViewedAccommodationInfo(
		LocalDateTime viewedAt,
		Long accommodationId,
		String accommodationName,
		String thumbnailUrl,
		AddressResponse.AddressSummaryInfo addressInfo,
		ReviewResponse.ReviewSummary reviewSummary,
		Boolean isInWishlist
	) {
		public static RecentlyViewedAccommodationInfo from(LocalDateTime viewedAt, Accommodation accommodation,
			ReviewResponse.ReviewSummary reviewSummary, boolean isInWishlist) {
			return RecentlyViewedAccommodationInfo.builder()
				.viewedAt(viewedAt)
				.accommodationId(accommodation.getId())
				.accommodationName(accommodation.getName())
				.thumbnailUrl(accommodation.getThumbnailUrl())
				.addressInfo(AddressResponse.AddressSummaryInfo.from(accommodation.getAddress()))
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
}
