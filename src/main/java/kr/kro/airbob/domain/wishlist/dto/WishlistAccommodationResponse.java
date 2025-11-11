package kr.kro.airbob.domain.wishlist.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import lombok.AccessLevel;
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
		long accommodationId,
		String accommodationName,
		String thumbnailUrl,
		String locationSummary,
		BigDecimal averageRating,
		int reviewCount,
		LocalDateTime createdAt,
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
				accommodation.getId(),
				accommodation.getName(),
				accommodation.getThumbnailUrl(),
				String.format("%s %s", accommodation.getAddress().getDistrict(),accommodation.getAddress().getStreet()),
				averageRating,
				reviewCount,
				createdAt,
				true // wishlist_accommodation 테이블에서 조회한 것이므로 true
			);
		}
	}
}
