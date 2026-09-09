package kr.kro.airbob.domain.wishlist.repository.projection;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

public record WishlistSummaryProjection(
	Long id,
	String name,
	LocalDateTime createdAt,
	Integer accommodationCount,
	String thumbnailImageUrl,
	Long wishlistAccommodationId
) {
	@QueryProjection
	public WishlistSummaryProjection {
	}
}
