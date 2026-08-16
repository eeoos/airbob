package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 숙소 상세 캐시 정책 값과 TTL 분산기를 애플리케이션 빈으로 등록
 */
@Configuration
@EnableConfigurationProperties(AccommodationDetailCacheProperties.class)
public class AccommodationDetailCacheConfiguration {

	@Bean
	AccommodationDetailCacheJitter accommodationDetailCacheJitter() {
		return new AccommodationDetailCacheJitter();
	}
}
