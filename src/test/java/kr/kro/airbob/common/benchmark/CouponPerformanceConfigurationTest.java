package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.kro.airbob.config.SchedulingConfig;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryProductionProfileGuard;

class CouponPerformanceConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("spring.profiles.active=aws,coupon-performance", "BENCHMARK_READ_MODEL_TOKEN=test-token")
		.withUserConfiguration(CouponPerformanceConfiguration.class, CouponPerformanceIsolationFilter.class,
			SchedulingConfig.class, AccommodationInventoryProductionProfileGuard.class);

	@Test
	void includesCouponApiWithoutStartingBackgroundWorkOrOtherExperiments() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(SchedulingConfig.class);
			assertThat(context.getEnvironment().getActiveProfiles()).containsExactlyInAnyOrder(
				"aws", "coupon-performance", "coupon-benchmark");
			assertThat(context).hasSingleBean(CouponPerformanceIsolationFilter.class);
			assertThat(context.getEnvironment().getProperty("spring.flyway.target")).isNull();
			assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.read-only")).isEqualTo("false");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
		"reservation.inventory.startup.enabled=true", "payment.toss.enabled=true",
		"cloud.aws.s3.write-enabled=true", "spring.datasource.hikari.read-only=true", "spring.flyway.enabled=false"})
	void rejectsEnvironmentOverridesThatBreakTheExperiment(String property) {
		runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void rejectsReadModelProfileWhichWouldBlockCouponWrites() {
		runner.withPropertyValues("spring.profiles.active=aws,coupon-performance,read-model-benchmark")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void normalAwsStillRequiresReservationInventoryLifecycle() {
		runner.withPropertyValues("spring.profiles.active=aws", "reservation.inventory.startup.enabled=false")
			.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"27", "28"})
	void validatesTheRestoredSchemaWithoutApplyingMigrations(String version) {
		runner.run(context -> {
			Flyway flyway = mock(Flyway.class);
			MigrationInfoService info = mock(MigrationInfoService.class);
			MigrationInfo current = mock(MigrationInfo.class);
			when(flyway.info()).thenReturn(info);
			when(info.current()).thenReturn(current);
			when(current.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
			var strategy = context.getBean(FlywayMigrationStrategy.class);
			if (version.equals("28")) {
				strategy.migrate(flyway);
			} else {
				assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);
			}
			verify(flyway).validate();
			verify(flyway).info();
			verifyNoMoreInteractions(flyway);
		});
	}
}
