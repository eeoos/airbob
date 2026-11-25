package kr.kro.airbob.core.domain.accommodation.location.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.client.google.GoogleGeocodingClient;

@Configuration(proxyBeanMethods = false)
public class GoogleGeocodingConfiguration {

	// todo: 배포 후엔 api ip 제한 걸기
	@Bean
	public GoogleGeocodingClient googleGeocodingClient(
		@Qualifier("generalRestClient")
		RestClient restClient,
		@Value("${google.api.key}")
		String googleApiKey) {
		return new GoogleGeocodingClient(restClient, googleApiKey);
	}
}
