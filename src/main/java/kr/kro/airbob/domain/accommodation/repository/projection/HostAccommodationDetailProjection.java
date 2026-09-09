package kr.kro.airbob.domain.accommodation.repository.projection;

import java.time.LocalTime;

import com.querydsl.core.annotations.QueryProjection;

public record HostAccommodationDetailProjection(
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
	String street,
	String addressDetail,
	String postalCode,
	Integer maxOccupancy,
	Integer infantOccupancy,
	Integer petOccupancy
) {
	@QueryProjection
	public HostAccommodationDetailProjection {
	}
}
