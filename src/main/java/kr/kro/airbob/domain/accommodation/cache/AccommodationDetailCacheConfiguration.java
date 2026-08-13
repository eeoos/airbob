package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccommodationDetailCacheProperties.class)
public class AccommodationDetailCacheConfiguration {

	@Bean
	AccommodationDetailCacheJitter accommodationDetailCacheJitter() {
		return new AccommodationDetailCacheJitter();
	}
}
