package kr.kro.airbob.config;

import java.util.Locale;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("performance-lab")
@EnableConfigurationProperties({RedisProperties.class, AccommodationDetailRedisProperties.class})
public class PerformanceLabRedisEndpointConfiguration {

	@Bean
	InitializingBean performanceLabRedisEndpointValidator(
		RedisProperties general,
		AccommodationDetailRedisProperties cache
	) {
		return () -> {
			String generalHost = normalize(general.getHost());
			String cacheHost = normalize(cache.host());
			boolean sameEndpoint = generalHost.equals(cacheHost)
				&& general.getPort() == cache.port();
			Assert.state(!sameEndpoint,
				"performance-lab requires distinct general and accommodation cache Redis endpoints");
		};
	}

	private String normalize(String host) {
		return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
	}
}
