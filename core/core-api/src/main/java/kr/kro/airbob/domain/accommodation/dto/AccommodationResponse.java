package kr.kro.airbob.domain.accommodation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationDetailProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationResponse {

	@Schema(name = "AccommodationResponseCreate")
	public record Create(
		@Schema(description = "생성한 숙소 ID. 이후 수정·이미지·게시 API의 accommodationId로 사용합니다.", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Builder
	@Schema(name = "AccommodationResponseHostAccommodationInfo")
	public record HostAccommodationInfo(
		@Schema(description = "숙소 ID", example = "101")
		Long id,
		@Schema(nullable = true)
		String name,
		@Schema(nullable = true)
		String thumbnailUrl,
		@Schema(description = "숙소 상태. 목록에서는 DELETED를 제외합니다.", example = "DRAFT")
		AccommodationStatus status,
		@Schema(nullable = true)
		String type,
		AddressResponse.AddressSummaryInfo addressSummary,
		// Integer basePrice,
		// ReviewResponse.ReviewSummary reviewSummary,
		@Schema(description = "UTC 생성 시각. 생성 시각이 없는 과거 데이터는 null입니다.", nullable = true, example = "2027-01-01T00:00:00Z")
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
	@Schema(name = "AccommodationResponseHostAccommodationInfos")
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
	@Schema(name = "AccommodationResponseUnavailableDateRange")
	public record UnavailableDateRange(
		@Schema(description = "예약 불가 구간의 첫 숙박 날짜(포함)", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate startDate,
		@Schema(description = "예약 불가 구간의 종료 경계. 이 날짜의 밤은 포함하지 않습니다.", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate endDateExclusive
	) {
	}

	@Builder
	@Schema(name = "AccommodationResponseAvailability")
	public record Availability(
		@Schema(description = "숙소 현지 오늘. 예약 가능 기간의 시작(포함)", example = "2027-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate bookingWindowStartInclusive,
		@Schema(description = "시작일의 3개월 뒤. 이 날짜의 밤은 예약 범위에서 제외되며 체크아웃 날짜로는 사용할 수 있습니다.", example = "2027-04-01", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate bookingWindowEndExclusive,
		@Schema(description = "조회 시점에 점유 중인 연속 숙박 구간. 없으면 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		List<UnavailableDateRange> unavailableRanges
	) {
	}

	@Builder
	@Schema(name = "AccommodationResponseDetailInfo")
	public record DetailInfo(
		long id,
		String name,
		String description,
		String type,
		Long basePrice,
		@Schema(description = "저장된 통화. 미설정이면 null이며 예약 계산에서는 KRW를 사용합니다.", example = "KRW", nullable = true)
		String currency,
		@Schema(description = "숙소 현지 체크인 시간", example = "15:00:00")
		LocalTime checkInTime,
		@Schema(description = "숙소 현지 체크아웃 시간", example = "11:00:00")
		LocalTime checkOutTime,
		@Schema(description = "주소에서 계산한 IANA 시간대", example = "Asia/Seoul")
		String timeZoneId,

		@Schema(description = "현재 로그인 회원의 찜 여부. 비로그인이면 false입니다.", example = "false")
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
	@Schema(name = "AccommodationResponseHostDetail")
	public record HostDetail(
		long id,
		@Schema(nullable = true)
		String name,
		@Schema(nullable = true)
		String description,
		@Schema(nullable = true)
		String type,
		@Schema(nullable = true)
		Long basePrice,
		@Schema(nullable = true)
		String currency,
		@Schema(nullable = true, example = "15:00:00", description = "숙소 현지 체크인 시간")
		LocalTime checkInTime,
		@Schema(nullable = true, example = "11:00:00", description = "숙소 현지 체크아웃 시간")
		LocalTime checkOutTime,
		@Schema(nullable = true, example = "Asia/Seoul", description = "주소에서 계산한 시간대. 주소 미설정 초안이면 null입니다.")
		String timeZoneId,

		@Schema(description = "전체 주소. 주소 미설정 초안은 객체 내부 항목들이 null입니다.")
		AddressResponse.AddressInfo address,

		@Schema(description = "수용 인원 정책. 미설정 초안은 객체 내부 항목들이 null입니다.")
		PolicyResponse.PolicyInfo policy,

		@Schema(description = "편의시설 목록. 없으면 빈 배열이며 type은 공통 코드입니다.")
		List<AmenityResponse.AmenityInfo> amenities,

		@Schema(description = "이미지 ID 오름차순 목록. 없으면 빈 배열입니다.")
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
	@Schema(name = "AccommodationResponseRecentlyViewedAccommodationInfos")
	public record RecentlyViewedAccommodationInfos(
		@Schema(description = "현재 공개된 최근 본 숙소. 최대 100개이며 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<RecentlyViewedAccommodationInfo> accommodations,
		@Schema(description = "이번 응답 accommodations의 항목 수. 제외된 기록은 세지 않습니다.", example = "1", minimum = "0", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
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
	@Schema(name = "AccommodationResponseRecentlyViewedAccommodationInfo")
	public record RecentlyViewedAccommodationInfo(
		@Schema(description = "이 회원이 마지막으로 기록 API를 호출한 서버 UTC 시각. 밀리초 정밀도이며 재기록 시 갱신됩니다.", example = "2027-01-10T02:00:00.123Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant viewedAt,
		@Schema(description = "숙소 ID. 상세 조회와 기록 삭제에 사용합니다.", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long accommodationId,
		@Schema(description = "현재 숙소 이름. 과거 데이터는 null일 수 있습니다.", example = "서울 하우스", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String accommodationName,
		@Schema(description = "현재 대표 사진 URL. 없으면 null", example = "https://example.invalid/stay.jpg", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String thumbnailUrl,
		@Schema(description = "현재 주소 요약. 주소가 없는 과거 숙소도 각 필드가 null인 객체로 반환합니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.AddressSummaryInfo addressSummary,
		@Schema(description = "현재 게시된 리뷰 수와 평균 평점. 없으면 0건·0점", requiredMode = Schema.RequiredMode.REQUIRED)
		ReviewResponse.ReviewSummary reviewSummary,
		@Schema(description = "본인의 활성 위시리스트 중 하나라도 이 숙소를 포함하는지 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean isInWishlist
	) {
		public RecentlyViewedAccommodationInfo {
			// 요약 행 유무와 관계없이 리뷰 없음은 다른 숙소 응답처럼 0건·0점으로 표현한다.
			if (reviewSummary == null || Integer.valueOf(0).equals(reviewSummary.totalCount())) {
				reviewSummary = ReviewResponse.ReviewSummary.of(null, null);
			}
		}

		public static RecentlyViewedAccommodationInfo from(Instant viewedAt,
			RecentlyViewedAccommodationProjection accommodation, boolean isInWishlist) {
			ReviewResponse.ReviewSummary reviewSummary = ReviewResponse.ReviewSummary.of(
				accommodation.totalReviewCount(), accommodation.averageRating());
			return RecentlyViewedAccommodationInfo.builder()
				.viewedAt(viewedAt)
				.accommodationId(accommodation.accommodationId())
				.accommodationName(accommodation.name())
				.thumbnailUrl(accommodation.thumbnailUrl())
				.addressSummary(new AddressResponse.AddressSummaryInfo(
					accommodation.country(), accommodation.state(), accommodation.city(), accommodation.district()))
				.reviewSummary(reviewSummary)
				.isInWishlist(isInWishlist)
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
		@Schema(nullable = true)
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
