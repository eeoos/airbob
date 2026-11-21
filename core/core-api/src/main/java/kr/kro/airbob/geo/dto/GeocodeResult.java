package kr.kro.airbob.geo.dto;

import static kr.kro.airbob.geo.dto.GoogleGeocodeResponse.Geometry.*;

import lombok.Builder;

@Builder
public record GeocodeResult(
	Double latitude,
	Double longitude,
	String formattedAddress,
	Viewport viewport,
	boolean success
) {
	public static GeocodeResult success(double lat, double lng, String address, Viewport viewport) {
		return GeocodeResult.builder()
			.latitude(lat)
			.longitude(lng)
			.formattedAddress(address)
			.viewport(viewport)
			.success(true)
			.build();
	}

	public static GeocodeResult fail() {
		return GeocodeResult.builder()
			.latitude(0.0)
			.longitude(0.0)
			.formattedAddress("")
			.viewport(null)
			.success(false)
			.build();
	}
}
