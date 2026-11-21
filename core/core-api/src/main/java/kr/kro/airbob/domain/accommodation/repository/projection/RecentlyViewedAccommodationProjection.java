package kr.kro.airbob.domain.accommodation.repository.projection;

import java.math.BigDecimal;

import com.querydsl.core.annotations.QueryProjection;

public record RecentlyViewedAccommodationProjection(
	Long accommodationId,
	String name,
	String thumbnailUrl,
	String country,
	String state,
	String city,
	String district,
	Integer totalReviewCount,
	BigDecimal averageRating
) {
	@QueryProjection
	public RecentlyViewedAccommodationProjection {
	}
}
