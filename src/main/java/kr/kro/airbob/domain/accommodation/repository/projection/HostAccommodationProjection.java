package kr.kro.airbob.domain.accommodation.repository.projection;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;

public record HostAccommodationProjection(
	Long id,
	String name,
	String thumbnailUrl,
	AccommodationStatus status,
	String type,
	String country,
	String state,
	String city,
	String district,
	LocalDateTime createdAt
) {
	@QueryProjection
	public HostAccommodationProjection {
	}
}
