package kr.kro.airbob.core.domain.accommodation.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.client.google.GoogleGeocodeResponse;
import kr.kro.airbob.client.google.GoogleGeocodingClient;

class GoogleGeocodingAdapterTest {

	private final GoogleGeocodingClient client = mock(GoogleGeocodingClient.class);

	@Test
	void disabledServiceReturnsFailureBeforeTouchingGoogle() {
		GoogleGeocodingAdapter disabledService = new GoogleGeocodingAdapter(
			client,
			false);

		GeocodeResult result = disabledService.getCoordinates("서울");

		assertThat(result.success()).isFalse();
		then(client).shouldHaveNoInteractions();
	}

	@Test
	void okResponseMapsTheFirstResultCoordinates() {
		GoogleGeocodeResponse response = response("OK", List.of(
			result(37.5665, 126.978),
			result(35.1796, 129.0756)));
		given(client.geocode("서울")).willReturn(response);

		GeocodeResult result = new GoogleGeocodingAdapter(client, true).getCoordinates("서울");

		assertThat(result.success()).isTrue();
		assertThat(result.latitude()).isEqualTo(37.5665);
		assertThat(result.longitude()).isEqualTo(126.978);
	}

	@Test
	void nonOkStatusOrEmptyResultsReturnFailure() {
		GoogleGeocodeResponse zeroResults = response("ZERO_RESULTS", List.of());
		GoogleGeocodeResponse okWithoutResults = response("OK", List.of());
		given(client.geocode("없는 주소")).willReturn(zeroResults);
		given(client.geocode("빈 결과")).willReturn(okWithoutResults);
		GoogleGeocodingAdapter adapter = new GoogleGeocodingAdapter(client, true);

		assertThat(adapter.getCoordinates("없는 주소").success()).isFalse();
		assertThat(adapter.getCoordinates("빈 결과").success()).isFalse();
	}

	@Test
	void transportFailureReturnsFailure() {
		given(client.geocode("서울")).willThrow(new ResourceAccessException("connection refused"));

		GeocodeResult result = new GoogleGeocodingAdapter(client, true).getCoordinates("서울");

		assertThat(result.success()).isFalse();
	}

	private static GoogleGeocodeResponse response(String status, List<GoogleGeocodeResponse.Result> results) {
		GoogleGeocodeResponse response = mock(GoogleGeocodeResponse.class);
		given(response.getStatus()).willReturn(status);
		given(response.getResults()).willReturn(results);
		return response;
	}

	private static GoogleGeocodeResponse.Result result(double lat, double lng) {
		return new GoogleGeocodeResponse.Result("주소",
			new GoogleGeocodeResponse.Geometry(new GoogleGeocodeResponse.Geometry.Location(lat, lng), null));
	}
}
