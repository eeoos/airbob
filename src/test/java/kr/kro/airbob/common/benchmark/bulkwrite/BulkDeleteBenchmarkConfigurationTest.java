package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import kr.kro.airbob.config.SchedulingConfig;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryProductionProfileGuard;

class BulkDeleteBenchmarkConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("spring.profiles.active=aws,bulk-delete-benchmark",
			"BENCHMARK_BULK_WRITE_ENABLED=true", "BENCHMARK_APP_COMMIT=" + "a".repeat(40),
			"BENCHMARK_IMAGE_DIGEST=sha256:" + "b".repeat(64))
		.withUserConfiguration(BulkDeleteBenchmarkConfiguration.class, BulkDeleteBenchmarkIsolationFilter.class,
			BulkDeleteBenchmarkRuntimeIdentity.class, SchedulingConfig.class,
			AccommodationInventoryProductionProfileGuard.class);

	@ParameterizedTest
	@ValueSource(strings = {"aws", "dev"})
	void localAndAwsUseTheSameIsolatedDeleteProfile(String profile) {
		runner.withPropertyValues("spring.profiles.active=" + profile + ",bulk-delete-benchmark")
			.run(context -> {
				assertThat(context).hasNotFailed().doesNotHaveBean(SchedulingConfig.class);
				assertThat(context.getEnvironment().getActiveProfiles())
					.containsExactlyInAnyOrder(profile, "bulk-delete-benchmark", "bulk-write-benchmark");
				assertThat(context).hasSingleBean(BulkDeleteBenchmarkIsolationFilter.class);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
		"accommodation.detail-cache.enabled=true", "operator-alert.kafka.auto-startup=true",
		"accommodation.indexing.kafka.auto-startup=true", "accommodation.indexing.bootstrap.enabled=true",
		"accommodation.detail-cache.invalidation.kafka.auto-startup=true", "spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=update", "spring.datasource.hikari.read-only=true",
		"spring.jpa.properties.hibernate.show_sql=true", "reservation.inventory.startup.enabled=true",
		"reservation.inventory.seed.enabled=true", "payment.toss.enabled=true", "cloud.aws.s3.write-enabled=true",
		"benchmark.bulk-write.enabled=false", "benchmark.bulk-delete.app-commit=short",
		"benchmark.bulk-delete.image-digest=latest"})
	void rejectsOverridesThatChangeTheExperiment(String setting) {
		runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"oci", "performance-lab", "coupon-performance", "nplus1-benchmark", "dev"})
	void rejectsMixedProfiles(String profile) {
		runner.withPropertyValues("spring.profiles.active=aws,bulk-delete-benchmark," + profile)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void onlyDeleteExperimentRoutesReachTheNormalAuthChain() throws Exception {
		var identity = new BulkDeleteBenchmarkRuntimeIdentity();
		var filter = new BulkDeleteBenchmarkIsolationFilter(identity);
		for (String path : new String[] {"/api/v2/admin/benchmarks/bulk-write/wishlist-delete",
			"/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete", "/api/v1/auth/login"}) {
			var response = new MockHttpServletResponse();
			var request = new MockHttpServletRequest("POST", path);
			request.addHeader(BulkDeleteBenchmarkRuntimeIdentity.HEADER_NAME, identity.value());
			filter.doFilter(request, response,
				(incoming, result) -> ((MockHttpServletResponse)result).setStatus(204));
			assertThat(response.getStatus()).isEqualTo(204);
		}
		for (String path : new String[] {"/api/v2/admin/benchmarks/bulk-write/reservation-history-insert",
			"/api/v1/reservations/checkout", "/api/v1/members", "/api/v1/payments/confirm"}) {
			var response = new MockHttpServletResponse();
			filter.doFilter(new MockHttpServletRequest("POST", path), response,
				(request, result) -> fail("Unrelated API must not be served"));
			assertThat(response.getStatus()).isEqualTo(404);
		}
	}
}
