package kr.kro.airbob.client.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@DisplayName("Google Geocoding client")
class GoogleGeocodingClientTest {

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final GoogleGeocodingClient client = new GoogleGeocodingClient(builder.build(), "test-key");

	@Test
	void geocodeSendsTheEncodedAddressWithKeyAndKoreanLanguage() {
		server.expect(requestTo(
			"https://maps.googleapis.com/maps/api/geocode/json"
				+ "?address=%EC%84%9C%EC%9A%B8%20%EC%A4%91%EA%B5%AC&key=test-key&language=ko"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
				{"status":"OK","results":[{
				  "formatted_address":"대한민국 서울특별시 중구","place_id":"place-1","types":["locality"],
				  "geometry":{"location":{"lat":37.5638,"lng":126.9975},"location_type":"APPROXIMATE",
				    "viewport":{"northeast":{"lat":37.57,"lng":127.0},"southwest":{"lat":37.55,"lng":126.98}}}}]}
				""", MediaType.APPLICATION_JSON));

		GoogleGeocodeResponse response = client.geocode("서울 중구");

		assertThat(response.getStatus()).isEqualTo("OK");
		GoogleGeocodeResponse.Geometry geometry = response.getResults().getFirst().geometry();
		assertThat(geometry.location().lat()).isEqualTo(37.5638);
		assertThat(geometry.location().lng()).isEqualTo(126.9975);
		assertThat(geometry.viewport().northeast().lat()).isEqualTo(37.57);
		server.verify();
	}

	@Test
	void nonOkStatusIsReturnedForTheCallerToInterpret() {
		server.expect(requestTo(
			"https://maps.googleapis.com/maps/api/geocode/json?address=nowhere&key=test-key&language=ko"))
			.andRespond(withSuccess("{\"status\":\"ZERO_RESULTS\",\"results\":[]}", MediaType.APPLICATION_JSON));

		GoogleGeocodeResponse response = client.geocode("nowhere");

		assertThat(response.getStatus()).isEqualTo("ZERO_RESULTS");
		assertThat(response.getResults()).isEmpty();
		server.verify();
	}

	@Test
	void serverErrorSurfacesAsRestClientException() {
		server.expect(requestTo(
			"https://maps.googleapis.com/maps/api/geocode/json?address=seoul&key=test-key&language=ko"))
			.andRespond(withServerError());

		assertThatThrownBy(() -> client.geocode("seoul")).isInstanceOf(RestClientException.class);
		server.verify();
	}
}
