package kr.kro.airbob.core.domain.accommodation.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleGeocodingAdapterTest {

	@Test
	void disabledServiceReturnsFailureBeforeTouchingGoogle() {
		RestClient restClient = mock(RestClient.class);
		GoogleGeocodingAdapter disabledService = new GoogleGeocodingAdapter(
			restClient,
			false);

		GeocodeResult result = disabledService.getCoordinates("서울");

		assertThat(result.success()).isFalse();
		then(restClient).shouldHaveNoInteractions();
	}
}
