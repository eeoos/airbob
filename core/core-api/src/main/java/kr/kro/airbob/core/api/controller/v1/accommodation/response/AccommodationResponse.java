package kr.kro.airbob.core.api.controller.v1.accommodation.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.core.api.controller.v1.image.response.ImageResponse;
import kr.kro.airbob.core.api.controller.v1.member.response.MemberResponse;
import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationDetailSnapshot;
import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummary;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationDetail;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationDetailProjection;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationPage;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationProjection;
import kr.kro.airbob.core.domain.accommodation.PublicAccommodationDetail;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationAvailability;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationResponse {

	@Schema(name = "AccommodationResponseCreate")
	public record Create(
		@Schema(description = "생성한 숙소 ID. 이후 수정·이미지·게시 API의 accommodationId로 사용합니다.", example = "101", requiredMode = Schema.RequiredMode.REQUIRED) long id) {
	}

	@Builder
	@Schema(name = "AccommodationResponseHostAccommodationInfo")
	public record HostAccommodationInfo(
		@Schema(description = "숙소 ID", example = "101") Long id,
		@Schema(nullable = true) String name,
		@Schema(nullable = true) String thumbnailUrl,
		@Schema(description = "숙소 상태. 목록에서는 DELETED를 제외합니다.", example = "DRAFT") AccommodationStatus status,
		@Schema(nullable = true) String type,
		AddressResponse.AddressSummaryInfo addressSummary,
		// Integer basePrice,
		// ReviewSummary reviewSummary,
		@Schema(description = "UTC 생성 시각. 생성 시각이 없는 과거 데이터는 null입니다.", nullable = true, example = "2027-01-01T00:00:00Z") Instant createdAt) {
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
	@Schema(name = "AccommodationResponseHostAccommodationInfos")
	public record HostAccommodationInfos(
		List<HostAccommodationInfo> accommodations,
		CursorResponse.PageInfo pageInfo) {
		public static HostAccommodationInfos from(List<HostAccommodationInfo> accommodations,
			CursorResponse.PageInfo pageInfo) {
			return HostAccommodationInfos.builder()
				.accommodations(accommodations)
				.pageInfo(pageInfo)
				.build();
		}

		public static HostAccommodationInfos from(HostAccommodationPage page) {
			return from(page.accommodations().stream().map(HostAccommodationInfo::from).toList(), page.pageInfo());
		}
	}

	@Builder
	@Schema(name = "AccommodationResponseUnavailableDateRange")
	public record UnavailableDateRange(
		@Schema(description = "예약 불가 구간의 첫 숙박 날짜(포함)", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate startDate,
		@Schema(description = "예약 불가 구간의 종료 경계. 이 날짜의 밤은 포함하지 않습니다.", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate endDateExclusive) {
	}

	@Builder
	@Schema(name = "AccommodationResponseAvailability")
	public record Availability(
		@Schema(description = "숙소 현지 오늘. 예약 가능 기간의 시작(포함)", example = "2027-01-01", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate bookingWindowStartInclusive,
		@Schema(description = "시작일의 3개월 뒤. 이 날짜의 밤은 예약 범위에서 제외되며 체크아웃 날짜로는 사용할 수 있습니다.", example = "2027-04-01", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate bookingWindowEndExclusive,
		@Schema(description = "조회 시점에 점유 중인 연속 숙박 구간. 없으면 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED) List<UnavailableDateRange> unavailableRanges) {
		public static Availability from(AccommodationAvailability availability) {
			return new Availability(
				availability.startInclusive(),
				availability.endExclusive(),
				availability.unavailableRanges().stream()
					.map(range -> new UnavailableDateRange(range.startInclusive(), range.endExclusive()))
					.toList());
		}
	}

	@Builder
	@Schema(name = "AccommodationResponseDetailInfo")
	public record DetailInfo(
		long id,
		String name,
		String description,
		String type,
		Long basePrice,
		@Schema(description = "저장된 통화. 미설정이면 null이며 예약 계산에서는 KRW를 사용합니다.", example = "KRW", nullable = true) String currency,
		@Schema(description = "숙소 현지 체크인 시간", example = "15:00:00") LocalTime checkInTime,
		@Schema(description = "숙소 현지 체크아웃 시간", example = "11:00:00") LocalTime checkOutTime,
		@Schema(description = "주소에서 계산한 IANA 시간대", example = "Asia/Seoul") String timeZoneId,

		@Schema(description = "현재 로그인 회원의 찜 여부. 비로그인이면 false입니다.", example = "false") Boolean isInWishlist,
		AddressResponse.AddressSummaryInfo addressSummary,
		AddressResponse.Coordinate coordinate,

		MemberResponse.MemberInfo host,

		PolicyResponse.PolicyInfo policy,

		List<AmenityResponse.AmenityInfo> amenities,

		List<ImageResponse.ImageInfo> images,

		ReviewSummary reviewSummary

	) {
		public static DetailInfo from(PublicAccommodationDetail detail) {
			return from(detail.detail(), detail.inWishlist());
		}

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
				.addressSummary(toAddressSummary(snapshot.addressSummary()))
				.coordinate(toCoordinate(snapshot.coordinate()))
				.host(toHost(snapshot.host()))
				.policy(toPolicy(snapshot.policy()))
				.amenities(snapshot.amenities().stream()
					.map(amenity -> new AmenityResponse.AmenityInfo(amenity.type(), amenity.count()))
					.toList())
				.images(snapshot.images().stream().map(ImageResponse.ImageInfo::from).toList())
				.reviewSummary(toReviewSummary(snapshot.reviewSummary()))
				.build();
		}

		private static AddressResponse.AddressSummaryInfo toAddressSummary(
			AccommodationDetailSnapshot.AddressSummary address) {
			return address == null ? null : new AddressResponse.AddressSummaryInfo(
				address.country(), address.state(), address.city(), address.district());
		}

		private static AddressResponse.Coordinate toCoordinate(AccommodationDetailSnapshot.Coordinate coordinate) {
			return coordinate == null ? null
				: new AddressResponse.Coordinate(coordinate.latitude(), coordinate.longitude());
		}

		private static MemberResponse.MemberInfo toHost(AccommodationDetailSnapshot.Host host) {
			return host == null ? null
				: new MemberResponse.MemberInfo(host.id(), host.nickname(), host.thumbnailImageUrl());
		}

		private static PolicyResponse.PolicyInfo toPolicy(AccommodationDetailSnapshot.Policy policy) {
			return policy == null ? null : new PolicyResponse.PolicyInfo(
				policy.maxOccupancy(), policy.infantOccupancy(), policy.petOccupancy());
		}

		private static ReviewSummary toReviewSummary(AccommodationDetailSnapshot.ReviewSummary summary) {
			return summary == null ? null : new ReviewSummary(summary.totalCount(), summary.averageRating());
		}

	}

	@Builder
	@Schema(name = "AccommodationResponseHostDetail")
	public record HostDetail(
		long id,
		@Schema(nullable = true) String name,
		@Schema(nullable = true) String description,
		@Schema(nullable = true) String type,
		@Schema(nullable = true) Long basePrice,
		@Schema(nullable = true) String currency,
		@Schema(nullable = true, example = "15:00:00", description = "숙소 현지 체크인 시간") LocalTime checkInTime,
		@Schema(nullable = true, example = "11:00:00", description = "숙소 현지 체크아웃 시간") LocalTime checkOutTime,
		@Schema(nullable = true, example = "Asia/Seoul", description = "주소에서 계산한 시간대. 주소 미설정 초안이면 null입니다.") String timeZoneId,

		@Schema(description = "전체 주소. 주소 미설정 초안은 객체 내부 항목들이 null입니다.") AddressResponse.AddressInfo address,

		@Schema(description = "수용 인원 정책. 미설정 초안은 객체 내부 항목들이 null입니다.") PolicyResponse.PolicyInfo policy,

		@Schema(description = "편의시설 목록. 없으면 빈 배열이며 type은 공통 코드입니다.") List<AmenityResponse.AmenityInfo> amenities,

		@Schema(description = "이미지 ID 오름차순 목록. 없으면 빈 배열입니다.") List<ImageResponse.ImageInfo> images) {
		public static HostDetail from(HostAccommodationDetail detail) {
			return from(
				detail.accommodation(),
				detail.amenities().stream()
					.map(amenity -> new AmenityResponse.AmenityInfo(amenity.type(), amenity.count()))
					.toList(),
				detail.images().stream().map(ImageResponse.ImageInfo::from).toList());
		}

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
				.address(new AddressResponse.AddressInfo(accommodation.country(), accommodation.state(),
					accommodation.city(),
					accommodation.district(), accommodation.street(), accommodation.addressDetail(),
					accommodation.postalCode()))
				.policy(new PolicyResponse.PolicyInfo(
					accommodation.maxOccupancy(), accommodation.infantOccupancy(), accommodation.petOccupancy()))
				.amenities(amenityInfos)
				.images(imageInfos)
				.build();
		}
	}

	/**
	 * 예약
	 */
	@Builder
	@Schema(name = "AccommodationResponseAccommodationBasicInfo")
	public record AccommodationBasicInfo(
		long id,
		String name,
		@Schema(nullable = true) String thumbnailUrl) {
		public static AccommodationBasicInfo from(Accommodation accommodation) {
			return AccommodationBasicInfo.builder()
				.id(accommodation.getId())
				.name(accommodation.getName())
				.thumbnailUrl(accommodation.getThumbnailUrl())
				.build();
		}
	}

	/** 숙소의 리뷰 요약. 숙소 상세·목록, 위시리스트, 검색 응답이 함께 쓴다. 스키마 이름은 기존 계약을 유지한다. */
	@Builder
	@Schema(name = "ReviewResponseReviewSummary")
	public record ReviewSummary(
		@Schema(description = "게시된 리뷰 수. 없으면 0", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) Integer totalCount,
		@Schema(description = "게시된 리뷰의 평균 평점. 소수 둘째 자리까지 반올림하며 리뷰가 없으면 0", example = "4.50", minimum = "0", maximum = "5", requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal averageRating) {
		public static ReviewSummary of(Integer totalCount, BigDecimal averageRating) {
			return new ReviewSummary(
				totalCount == null ? 0 : totalCount,
				averageRating == null ? BigDecimal.ZERO : averageRating);
		}

		public static ReviewSummary of(AccommodationReviewSummary summary) {
			if (summary == null) {
				return of(null, null);
			}
			return of(
				summary.getTotalReviewCount(),
				summary.getAverageRating());
		}

	}

	private static Instant toUtcInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
	}
}
