package kr.kro.airbob.domain.wishlist.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
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
		BigDecimal averageRating,
		int reviewCount, // 추가
		LocalDateTime createdAt,
		String thumbnailUrl,
		List<AccommodationResponse.AmenityInfo> amenities,
		Boolean isInWishlist // 추가
	) {
		@QueryProjection
		public WishlistAccommodationInfo(long wishlistAccommodationId, String memo, long accommodationId, String accommodationName, BigDecimal averageRating, int reviewCount, LocalDateTime createdAt, String thumbnailUrl) {
			this(wishlistAccommodationId, memo, accommodationId, accommodationName, averageRating, reviewCount,createdAt, thumbnailUrl, new ArrayList<>(), true);
		}
	}
}
