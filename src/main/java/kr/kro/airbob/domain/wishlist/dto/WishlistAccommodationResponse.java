package kr.kro.airbob.domain.wishlist.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistAccommodationResponse {

	@Schema(name = "WishlistAccommodationResponseCreate")
	public record Create(
		@Schema(description = "저장된 숙소 연결 ID(wishlistAccommodationId). 메모 수정·숙소 삭제에 사용하며 숙소 ID나 목록 ID와 다릅니다.", example = "501", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "WishlistAccommodationResponseUpdate")
	public record Update(
		@Schema(description = "저장된 숙소 연결 ID(wishlistAccommodationId). 메모 수정·숙소 삭제에 사용하며 숙소 ID나 목록 ID와 다릅니다.", example = "501", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "WishlistAccommodationResponseWishlistAccommodationInfos")
	public record WishlistAccommodationInfos(
		@Schema(description = "현재 공개된 숙소만 추가 시각·연결 ID 내림차순으로 반환합니다. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<WishlistAccommodationInfo> wishlistAccommodations,
		@Schema(description = "다음 페이지 여부·커서·현재 숙소 수", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo,
		@Schema(description = "현재 위시리스트 이름. 빈 목록에서도 반환합니다.", example = "서울 여행", requiredMode = Schema.RequiredMode.REQUIRED)
		String wishlistName
	) {
	}

	@Schema(name = "WishlistAccommodationResponseWishlistAccommodationInfo")
	public record WishlistAccommodationInfo(
		@Schema(description = "숙소 연결 ID. 메모 수정·숙소 삭제의 wishlistAccommodationId", example = "501", requiredMode = Schema.RequiredMode.REQUIRED)
		long wishlistAccommodationId,
		@Schema(description = "저장한 메모. 최초 추가 시 null", nullable = true, example = "역에서 가까운 숙소", requiredMode = Schema.RequiredMode.REQUIRED)
		String memo,
		@Schema(description = "숙소를 목록에 추가한 UTC 시각. 메모 수정 시 바뀌지 않으며 과거 데이터는 null일 수 있습니다.", example = "2027-01-10T02:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(description = "숙소의 현재 ID·이름·썸네일", requiredMode = Schema.RequiredMode.REQUIRED)
		AccommodationResponse.AccommodationBasicInfo accommodation,
		@Schema(description = "숙소의 현재 주소 요약", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.AddressSummaryInfo addressSummary,
		@Schema(description = "현재 게시된 리뷰의 수와 평균 평점. 없으면 0건·0점", requiredMode = Schema.RequiredMode.REQUIRED)
		ReviewResponse.ReviewSummary reviewSummary,
		@Schema(description = "이 목록에 저장된 항목이므로 항상 true", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean isInWishlist
	) {
		@QueryProjection
		public WishlistAccommodationInfo(
			long wishlistAccommodationId,
			String memo,
			long accommodationId,
			String accommodationName,
			String thumbnailUrl,
			String country,
			String state,
			String city,
			String district,
			BigDecimal averageRating,
			Integer reviewCount,
			LocalDateTime createdAt) {

			this(
				wishlistAccommodationId,
				memo,
				createdAt == null ? null : createdAt.toInstant(ZoneOffset.UTC),
				new AccommodationResponse.AccommodationBasicInfo(accommodationId, accommodationName, thumbnailUrl),
				new AddressResponse.AddressSummaryInfo(country, state, city, district),
				ReviewResponse.ReviewSummary.of(reviewCount, averageRating),
				true // wishlist_accommodation 테이블에서 조회한 것이므로 true
			);
		}
	}
}
