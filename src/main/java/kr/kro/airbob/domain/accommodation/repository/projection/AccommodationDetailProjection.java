package kr.kro.airbob.domain.accommodation.repository.projection;

import java.math.BigDecimal;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;

public record AccommodationDetailProjection(
	Accommodation accommodation,
	Integer totalReviewCount,
	BigDecimal averageRating
) {
	@QueryProjection
	public AccommodationDetailProjection {
	}
}
