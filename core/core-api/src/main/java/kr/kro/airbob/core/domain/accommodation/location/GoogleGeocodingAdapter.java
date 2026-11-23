package kr.kro.airbob.core.domain.accommodation.location;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class GoogleGeocodingAdapter implements GeocodingGateway {

	public static final String OK = "OK";
	private final RestClient restClient;
	private final boolean enabled;

	// todo: 배포 후엔 api ip 제한 걸기
	@Value("${google.api.key}")
	private String googleApiKey;

	private static final String GEOCODING_API_URL = "https://maps.googleapis.com/maps/api/geocode/json";

	public GoogleGeocodingAdapter(@Qualifier("generalRestClient") RestClient restClient,
		@Value("${google.api.enabled:true}") boolean enabled) {
		this.restClient = restClient;
		this.enabled = enabled;
	}

	@Override
	public GeocodeResult getCoordinates(String address) {
		if (!enabled) {
			return GeocodeResult.fail();
		}

		try {

			URI baseUri = URI.create(GEOCODING_API_URL);

			String url = UriComponentsBuilder.fromUri(baseUri)
				.queryParam("address", address)
				.queryParam("key", googleApiKey)
				.queryParam("language", "ko")
				.build()
				.toUriString();

			GoogleGeocodeResponse response = restClient.get()
				.uri(url)
				.retrieve()
				.body(GoogleGeocodeResponse.class);

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
			location.lng(),
			result.formattedAddress()
		);
	}

}
