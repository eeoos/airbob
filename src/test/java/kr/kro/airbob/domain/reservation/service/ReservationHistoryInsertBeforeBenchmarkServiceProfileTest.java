package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;

class ReservationHistoryInsertBeforeBenchmarkServiceProfileTest {

	private final ApplicationContextRunner contextRunner =
		new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

	@Test
	void requiresBothProfileAndEnabledProperty() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(ReservationHistoryInsertBeforeBenchmarkService.class));
		contextRunner
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(ReservationHistoryInsertBeforeBenchmarkService.class));
		contextRunner
			.withInitializer(context ->
				context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(ReservationHistoryInsertBeforeBenchmarkService.class));
		contextRunner
			.withInitializer(context ->
				context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(ReservationHistoryInsertBeforeBenchmarkService.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(ReservationHistoryInsertBeforeBenchmarkService.class)
	static class TestConfiguration {

		@Bean
		ReservationRepository reservationRepository() {
			return mock(ReservationRepository.class);
		}

		@Bean
		ReservationHistoryRepository reservationHistoryRepository() {
			return mock(ReservationHistoryRepository.class);
		}

		@Bean
		ReservationInventoryService reservationInventoryService() {
			return mock(ReservationInventoryService.class);
		}

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}
	}
}
