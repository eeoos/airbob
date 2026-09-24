package kr.kro.airbob.domain.accommodation.cache.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccommodationDetailCacheBenchmarkControlTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AccommodationDetailCacheConfiguration.class)
		.withPropertyValues(
			"accommodation.detail-cache.enabled=true",
			"accommodation.detail-cache.ttl=10m", "accommodation.detail-cache.ttl-jitter=2m",
			"accommodation.detail-cache.negative-ttl=45s", "accommodation.detail-cache.negative-ttl-jitter=15s",
			"accommodation.detail-cache.lock-wait=2s", "accommodation.detail-cache.local-load-wait=5s",
			"accommodation.detail-cache.load-permit-ttl=30s",
			"accommodation.detail-cache.redis-connect-timeout=1s",
			"accommodation.detail-cache.redis-command-timeout=1s");

	@Test
	void normalProfileKeepsCoalescingByDefault() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AccommodationDetailCacheProperties.class).localLoadCoalescingEnabled())
				.isTrue();
		});
	}

	@Test
	void normalProfileCannotDisableCoalescing() {
		runner.withPropertyValues("accommodation.detail-cache.local-load-coalescing-enabled=false")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
					"Disabling accommodation detail local load coalescing requires cache-benchmark profile");
			});
	}

	@Test
	void benchmarkProfileAllowsTheControlWithoutDisablingRedis() {
		runner.withPropertyValues("spring.profiles.active=cache-benchmark",
			"accommodation.detail-cache.local-load-coalescing-enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var properties = context.getBean(AccommodationDetailCacheProperties.class);
				assertThat(properties.enabled()).isTrue();
				assertThat(properties.localLoadCoalescingEnabled()).isFalse();
			});
	}
}
