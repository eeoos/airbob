package kr.kro.airbob.core.domain.accommodation.location;

import lombok.Builder;

@Builder
public record GeocodeResult(
	Double latitude,
	Double longitude,
	boolean success
) {
	public static GeocodeResult success(double lat, double lng) {
		return GeocodeResult.builder()
			.latitude(lat)
			.longitude(lng)
			.success(true)
			.build();
	}

	public static GeocodeResult fail() {
		return GeocodeResult.builder()
			.latitude(0.0)
			.longitude(0.0)
			.success(false)
			.build();
	}
}
