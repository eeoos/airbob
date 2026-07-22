package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.code.CommonCodeService;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;

class AccommodationAmenityDeleteBenchmarkProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void everyU4BeanRequiresBothProfileAndEnabledProperty() {
		assertU4BeansAbsent(contextRunner);
		assertU4BeansAbsent(contextRunner.withPropertyValues("benchmark.bulk-write.enabled=true"));
		assertU4BeansAbsent(contextRunner.withInitializer(context ->
			context.getEnvironment().setActiveProfiles("bulk-write-benchmark")));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBenchmarkService.class);
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBenchmarkFixtureService.class);
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBeforeBenchmarkService.class);
			});
	}

	private void assertU4BeansAbsent(ApplicationContextRunner runner) {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBenchmarkService.class);
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBenchmarkFixtureService.class);
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBeforeBenchmarkService.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@Import({
		AccommodationAmenityDeleteBenchmarkService.class,
		AccommodationAmenityDeleteBenchmarkFixtureService.class,
		AccommodationAmenityDeleteBeforeBenchmarkService.class
	})
	static class TestConfiguration {

		@Bean AccommodationService accommodationService() { return mock(AccommodationService.class); }
		@Bean AccommodationAmenityRepository accommodationAmenityRepository() {
			return mock(AccommodationAmenityRepository.class);
		}
		@Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
		@Bean CommonCodeService commonCodeService() { return mock(CommonCodeService.class); }
		@Bean BulkOperationMonitor bulkOperationMonitor() { return mock(BulkOperationMonitor.class); }
		@Bean BulkWriteBenchmarkDatabaseGuard databaseGuard() {
			return mock(BulkWriteBenchmarkDatabaseGuard.class);
		}
	}
}
