package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;

class AccommodationAmenityDeleteBenchmarkProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void everyBenchmarkBeanRequiresBothProfileAndEnabledProperty() {
		assertBenchmarkBeansAbsent(contextRunner);
		assertBenchmarkBeansAbsent(contextRunner.withPropertyValues("benchmark.bulk-write.enabled=true"));
		assertBenchmarkBeansAbsent(contextRunner.withInitializer(context ->
			context.getEnvironment().setActiveProfiles("bulk-write-benchmark")));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBenchmarkService.class);
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBenchmarkFixtureService.class);
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteBeforeBenchmarkService.class);
				assertThat(context).hasSingleBean(AccommodationAmenityDeleteAfterBenchmarkService.class);
			});
	}

	private void assertBenchmarkBeansAbsent(ApplicationContextRunner runner) {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBenchmarkService.class);
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBenchmarkFixtureService.class);
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteBeforeBenchmarkService.class);
			assertThat(context).doesNotHaveBean(AccommodationAmenityDeleteAfterBenchmarkService.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@Import({
		AccommodationAmenityDeleteBenchmarkService.class,
		AccommodationAmenityDeleteBenchmarkFixtureService.class,
		AccommodationAmenityDeleteBeforeBenchmarkService.class,
		AccommodationAmenityDeleteAfterBenchmarkService.class
	})
	static class TestConfiguration {

		@Bean AccommodationCommandService accommodationCommandService() {
			return mock(AccommodationCommandService.class);
		}
		@Bean AccommodationAmenityRepository accommodationAmenityRepository() {
			return mock(AccommodationAmenityRepository.class);
		}
		@Bean AccommodationRepository accommodationRepository() { return mock(AccommodationRepository.class); }
		@Bean AccommodationHistoryRepository accommodationHistoryRepository() {
			return mock(AccommodationHistoryRepository.class);
		}
		@Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
		@Bean CommonCodeService commonCodeService() { return mock(CommonCodeService.class); }
		@Bean Clock clock() { return Clock.systemUTC(); }
		@Bean BulkOperationMonitor bulkOperationMonitor() { return mock(BulkOperationMonitor.class); }
		@Bean BulkWriteBenchmarkDatabaseGuard databaseGuard() {
			return mock(BulkWriteBenchmarkDatabaseGuard.class);
		}
	}
}
