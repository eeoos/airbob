package kr.kro.airbob.core.domain.accommodation.location;

import lombok.Builder;

@Builder
public record GeocodeResult(
	Double latitude,
	Double longitude,
	String formattedAddress,
	boolean success
) {
	public static GeocodeResult success(double lat, double lng, String address) {
		return GeocodeResult.builder()
			.latitude(lat)
			.longitude(lng)
			.formattedAddress(address)
			.success(true)
			.build();
	}

	public static GeocodeResult fail() {
		return GeocodeResult.builder()
			.latitude(0.0)
			.longitude(0.0)
			.formattedAddress("")
			.success(false)
			.build();
	}
}
