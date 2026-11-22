package kr.kro.airbob.core.domain.wishlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

/**
 * A published accommodation saved in a wishlist, with its current card fields. createdAt is the stored UTC time
 * the accommodation was added and doubles as the cursor key. averageRating and reviewCount are null when the
 * accommodation has no review summary row.
 */
public record WishlistAccommodationItem(
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
	LocalDateTime createdAt
) {
	@QueryProjection
	public WishlistAccommodationItem {
	}
}
