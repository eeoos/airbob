package kr.kro.airbob.core.domain.wishlist;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

/**
 * One of a member's active wishlists in the list view, read from the denormalized count and representative
 * columns. createdAt is the stored UTC time and doubles as the cursor key.
 *
 * @param wishlistAccommodationId the saved link for the accommodation the request asked about; null when the
 *                                request named none or the list does not contain it
 */
public record WishlistSummary(
	Long id,
	String name,
	LocalDateTime createdAt,
	Integer accommodationCount,
	String thumbnailImageUrl,
	Long wishlistAccommodationId
) {
	@QueryProjection
	public WishlistSummary {
	}
}
