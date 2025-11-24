package kr.kro.airbob.domain.wishlist.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistAccommodationResponse {

	public record Create(
		long id
	) {
	}

	public record Update(
		long id
	) {
	}

	public record WishlistAccommodationInfos(
		List<WishlistAccommodationInfo> wishlistAccommodations,
		CursorResponse.PageInfo pageInfo
	) {
	}

	public record WishlistAccommodationInfo(
		long wishlistAccommodationId,
		String memo,
		LocalDateTime createdAt,
		AccommodationResponse.AccommodationBasicInfo accommodationInfo,
		AddressResponse.AddressSummaryInfo addressInfo,
		ReviewResponse.ReviewSummary reviewSummary,
		Boolean isInWishlist
	) {
		@QueryProjection
		public WishlistAccommodationInfo(
			long wishlistAccommodationId,
			String memo,
			Accommodation accommodation,
			BigDecimal averageRating,
			int reviewCount,
			LocalDateTime createdAt) {

			this(
				wishlistAccommodationId,
				memo,
				createdAt,
				AccommodationResponse.AccommodationBasicInfo.from(accommodation),
				AddressResponse.AddressSummaryInfo.from(accommodation.getAddress()),
				ReviewResponse.ReviewSummary.builder()
					.averageRating(averageRating)
					.totalCount(reviewCount)
					.build(),
				true // wishlist_accommodation 테이블에서 조회한 것이므로 true
			);
		}
	}
}
