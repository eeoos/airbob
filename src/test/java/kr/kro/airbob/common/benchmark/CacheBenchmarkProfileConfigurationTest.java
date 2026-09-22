package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import kr.kro.airbob.config.PerformanceLabRedisEndpointConfiguration;
import kr.kro.airbob.config.SchedulingConfig;
import kr.kro.airbob.domain.accommodation.api.AccommodationDetailBenchmarkController;
import kr.kro.airbob.domain.accommodation.service.AccommodationDetailBenchmarkService;
import kr.kro.airbob.domain.accommodation.service.AccommodationDetailReader;
import kr.kro.airbob.domain.coupon.service.CouponLegacyIssuanceRolloutGuard;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryProductionProfileGuard;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryReadiness;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryRetentionScheduler;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedScheduler;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryStartupBootstrap;
import kr.kro.airbob.search.infrastructure.elasticsearch.AccommodationIndexAliasBootstrap;
import kr.kro.airbob.search.infrastructure.elasticsearch.AccommodationIndexAliasReadiness;

class CacheBenchmarkProfileConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues(
			"spring.profiles.active=aws,cache-benchmark",
			"BENCHMARK_READ_MODEL_TOKEN=cache-test-token",
			"spring.data.redis.host=localhost",
			"spring.data.redis.port=6379",
			"accommodation.detail-cache.redis.host=localhost",
			"accommodation.detail-cache.redis.port=6380"
		)
		.withUserConfiguration(
			CacheBenchmarkIsolationFilter.class,
			CacheBenchmarkFlywayConfiguration.class,
			BenchmarkAccessGuard.class,
			AccommodationDetailBenchmarkController.class,
			AccommodationDetailBenchmarkService.class,
			PerformanceLabRedisEndpointConfiguration.class,
			SchedulingConfig.class,
			AccommodationInventoryProductionProfileGuard.class,
			AccommodationInventoryReadiness.class,
			AccommodationInventoryStartupBootstrap.class,
			AccommodationInventorySeedScheduler.class,
			AccommodationInventoryRetentionScheduler.class,
			AccommodationIndexAliasReadiness.class,
			CouponLegacyIssuanceRolloutGuard.class
		)
		.withBean(AccommodationDetailReader.class, () -> mock(AccommodationDetailReader.class))
		.withBean(AccommodationIndexAliasBootstrap.class, () -> mock(AccommodationIndexAliasBootstrap.class));

	@Test
	void isolatesTheCacheExperimentWithoutImportingTheV27PerformanceLabProfile() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			Environment environment = context.getEnvironment();
			assertThat(environment.getActiveProfiles()).containsExactly("aws", "cache-benchmark");
			assertThat(environment.getProperty("spring.flyway.target")).isNull();
			assertThat(context).hasSingleBean(CacheBenchmarkIsolationFilter.class);
			assertThat(context).hasSingleBean(AccommodationDetailBenchmarkController.class);
			assertThat(context).hasSingleBean(AccommodationDetailBenchmarkService.class);
			assertThat(context).hasSingleBean(PerformanceLabRedisEndpointConfiguration.class);
			assertThat(environment.getProperty("accommodation.detail-cache.enabled", Boolean.class)).isTrue();
			assertThat(environment.getProperty("spring.datasource.hikari.read-only", Boolean.class)).isTrue();
			assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
			context.getBean(BenchmarkAccessGuard.class).verify("cache-test-token");
		});
	}

	@Test
	void noWriterLifecycleOrExternalSideEffectsRunAtStartup() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(SchedulingConfig.class);
			assertThat(context).doesNotHaveBean(AccommodationInventoryStartupBootstrap.class);
			assertThat(context).doesNotHaveBean(AccommodationInventorySeedScheduler.class);
			assertThat(context).doesNotHaveBean(AccommodationInventoryRetentionScheduler.class);
			assertThat(context).doesNotHaveBean(CouponLegacyIssuanceRolloutGuard.class);
			assertThat(context.getBean(AccommodationInventoryReadiness.class).health().getStatus())
				.isEqualTo(Status.UP);
			assertThat(context.getBean(AccommodationIndexAliasReadiness.class).shouldAutoStart()).isFalse();
			verifyNoInteractions(context.getBean(AccommodationIndexAliasBootstrap.class));
			for (String property : List.of(
				"spring.kafka.listener.auto-startup", "spring.kafka.admin.auto-create",
				"accommodation.indexing.bootstrap.enabled", "accommodation.indexing.kafka.auto-startup",
				"accommodation.detail-cache.invalidation.kafka.auto-startup", "operator-alert.kafka.auto-startup",
				"payment.toss.enabled", "google.api.enabled", "operator-alert.slack.enabled",
				"cloud.aws.s3.write-enabled", "spring.jpa.show-sql",
				"spring.jpa.properties.hibernate.show_sql", "spring.jpa.properties.hibernate.generate_statistics"
			)) {
				assertThat(context.getEnvironment().getProperty(property, Boolean.class)).as(property).isFalse();
			}
		});
	}

	@Test
	void rejectsSharingTheSessionRedisEndpointEvenWhenDatabaseNumbersDiffer() {
		runner.withPropertyValues(
			"accommodation.detail-cache.redis.port=6379",
			"accommodation.detail-cache.redis.database=1"
		).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(
				"performance-lab/cache-benchmark requires distinct general and accommodation cache Redis endpoints");
		});
	}
}
