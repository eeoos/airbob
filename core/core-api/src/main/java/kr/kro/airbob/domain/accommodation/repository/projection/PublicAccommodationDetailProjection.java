package kr.kro.airbob.domain.accommodation.repository.projection;

import java.math.BigDecimal;
import java.time.LocalTime;

import com.querydsl.core.annotations.QueryProjection;

public record PublicAccommodationDetailProjection(
	Long id,
	String name,
	String description,
	String type,
	Long basePrice,
	String currency,
	LocalTime checkInTime,
	LocalTime checkOutTime,
	String timeZoneId,
	String country,
	String state,
	String city,
	String district,
	Double latitude,
	Double longitude,
	Long hostId,
	String hostNickname,
	String hostThumbnailImageUrl,
	Integer maxOccupancy,
	Integer infantOccupancy,
	Integer petOccupancy,
	Integer totalReviewCount,
	BigDecimal averageRating
) {
	@QueryProjection
	public PublicAccommodationDetailProjection {
	}
}
