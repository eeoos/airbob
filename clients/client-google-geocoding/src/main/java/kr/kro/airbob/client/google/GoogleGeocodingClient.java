package kr.kro.airbob.client.google;

import java.net.URI;

import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP transport for the Google Geocoding API.
 *
 * <p>It does not interpret outcomes. Callers decide what a non-{@code OK} status or an empty result
 * means. Transport failures and unreadable bodies surface as Spring's
 * {@link org.springframework.web.client.RestClientException}.
 */
public class GoogleGeocodingClient {

	static final String GEOCODING_API_URL = "https://maps.googleapis.com/maps/api/geocode/json";

	private final RestClient restClient;
	private final String apiKey;

	public GoogleGeocodingClient(RestClient restClient, String apiKey) {
		this.restClient = restClient;
		this.apiKey = apiKey;
	}

	public GoogleGeocodeResponse geocode(String address) {
		String url = UriComponentsBuilder.fromUri(URI.create(GEOCODING_API_URL))
			.queryParam("address", address)
			.queryParam("key", apiKey)
			.queryParam("language", "ko")
			.build()
			.toUriString();

		return restClient.get()
			.uri(url)
			.retrieve()
			.body(GoogleGeocodeResponse.class);
	}
}
