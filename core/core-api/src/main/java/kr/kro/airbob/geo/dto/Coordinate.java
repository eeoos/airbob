package kr.kro.airbob.geo.dto;

import lombok.Builder;

@Builder
public record Coordinate(
	Double latitude,
	Double longitude
) {
}
