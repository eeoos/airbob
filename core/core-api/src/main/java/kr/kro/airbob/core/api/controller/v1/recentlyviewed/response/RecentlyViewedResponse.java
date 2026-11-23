package kr.kro.airbob.core.api.controller.v1.recentlyviewed.response;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.core.api.controller.v1.accommodation.response.AccommodationResponse;
import kr.kro.airbob.core.api.controller.v1.accommodation.response.AddressResponse;
import kr.kro.airbob.core.domain.accommodation.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.core.domain.recentlyviewed.RecentlyViewedAccommodation;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

/** 최근 본 숙소 응답. 스키마 이름은 accommodation 응답에 있던 때와 같게 유지한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecentlyViewedResponse {

	@Builder
	@Schema(name = "AccommodationResponseRecentlyViewedAccommodationInfos")
	public record RecentlyViewedAccommodationInfos(
		@Schema(description = "현재 공개된 최근 본 숙소. 최대 100개이며 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<RecentlyViewedAccommodationInfo> accommodations,
		@Schema(description = "이번 응답 accommodations의 항목 수. 제외된 기록은 세지 않습니다.", example = "1", minimum = "0", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
		int totalCount
	) {
		public static RecentlyViewedAccommodationInfos from(List<RecentlyViewedAccommodation> accommodations) {
			List<RecentlyViewedAccommodationInfo> accommodationInfos = accommodations.stream()
				.map(RecentlyViewedAccommodationInfo::from)
				.toList();
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
		AccommodationResponse.ReviewSummary reviewSummary,
		@Schema(description = "본인의 활성 위시리스트 중 하나라도 이 숙소를 포함하는지 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean isInWishlist
	) {
		public RecentlyViewedAccommodationInfo {
			// 요약 행 유무와 관계없이 리뷰 없음은 다른 숙소 응답처럼 0건·0점으로 표현한다.
			if (reviewSummary == null || Integer.valueOf(0).equals(reviewSummary.totalCount())) {
				reviewSummary = AccommodationResponse.ReviewSummary.of(null, null);
			}
		}

		public static RecentlyViewedAccommodationInfo from(RecentlyViewedAccommodation recentlyViewed) {
			return from(recentlyViewed.viewedAt(), recentlyViewed.accommodation(), recentlyViewed.wishlisted());
		}

		public static RecentlyViewedAccommodationInfo from(Instant viewedAt,
			RecentlyViewedAccommodationProjection accommodation, boolean isInWishlist) {
			AccommodationResponse.ReviewSummary reviewSummary = AccommodationResponse.ReviewSummary.of(
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
}
