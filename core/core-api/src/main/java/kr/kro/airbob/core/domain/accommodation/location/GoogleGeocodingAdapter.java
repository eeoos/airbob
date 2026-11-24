package kr.kro.airbob.core.domain.accommodation.location;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import kr.kro.airbob.client.google.GoogleGeocodeResponse;
import kr.kro.airbob.client.google.GoogleGeocodingClient;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP transport lives in {@link GoogleGeocodingClient} (clients:client-google-geocoding); this adapter
 * owns the enabled switch, the {@code OK} status check, and mapping failures to {@link GeocodeResult#fail()}.
 */
@Component
@Slf4j
public class GoogleGeocodingAdapter implements GeocodingGateway {

	public static final String OK = "OK";
	private final GoogleGeocodingClient googleGeocodingClient;
	private final boolean enabled;

	public GoogleGeocodingAdapter(GoogleGeocodingClient googleGeocodingClient,
		@Value("${google.api.enabled:true}") boolean enabled) {
		this.googleGeocodingClient = googleGeocodingClient;
		this.enabled = enabled;
	}

	@Override
	public GeocodeResult getCoordinates(String address) {
		if (!enabled) {
			return GeocodeResult.fail();
		}

		try {
			GoogleGeocodeResponse response = googleGeocodingClient.geocode(address);

			if (response != null && OK.equals(response.getStatus()) && !response.getResults().isEmpty()) {
				GoogleGeocodeResponse.Result result = response.getResults().getFirst();

				return buildGeocodeResult(result);
			} else {
				log.warn("Geocoding 실패: {}, status: {}", address, response != null ? response.getStatus() : "null");
				return GeocodeResult.fail();
			}
		} catch (Exception e) {
			log.error("Geocoding API 호출 중 오류 발생: {}", address, e);
			return GeocodeResult.fail();
		}
	}

	private GeocodeResult buildGeocodeResult(GoogleGeocodeResponse.Result result) {
		GoogleGeocodeResponse.Geometry.Location location = result.geometry().location();

		return GeocodeResult.success(
			location.lat(),
			location.lng()
		);
	}

}
