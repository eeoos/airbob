package kr.kro.airbob.geo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.geo.ViewportAdjuster;
import kr.kro.airbob.geo.dto.GeocodeResult;

class GoogleGeocodingServiceTest {

	@Test
	void disabledServiceReturnsFailureBeforeTouchingGoogle() {
		RestClient restClient = mock(RestClient.class);
		GoogleGeocodingService disabledService = new GoogleGeocodingService(
			restClient,
			new ViewportAdjuster(),
			false);

		GeocodeResult result = disabledService.getCoordinates("서울");

		assertThat(result.success()).isFalse();
		then(restClient).shouldHaveNoInteractions();
	}
}
