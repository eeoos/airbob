package kr.kro.airbob.domain.reservation.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkResponse;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkService;

@DisplayName("ReservationHistory INSERT 벌크 쓰기 벤치마크 API 테스트")
class ReservationHistoryInsertBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("profile과 enabled property 중 하나라도 없으면 API가 노출되지 않는다")
	void requiresProfileAndProperty() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(ReservationHistoryInsertBenchmarkController.class));
		contextRunner
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(ReservationHistoryInsertBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(ReservationHistoryInsertBenchmarkController.class));
	}

	@Test
	@DisplayName("profile과 enabled property를 함께 켜면 API가 노출된다")
	void profileAndPropertyEnableController() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(ReservationHistoryInsertBenchmarkController.class));
	}

	@Test
	@DisplayName("전용 토큰을 먼저 검증하고 Before 오케스트레이터를 호출한다")
	void verifiesTokenAndDelegates() {
		ReservationHistoryInsertBenchmarkService service = mock(ReservationHistoryInsertBenchmarkService.class);
		BulkWriteBenchmarkAccessGuard guard = mock(BulkWriteBenchmarkAccessGuard.class);
		ReservationHistoryInsertBenchmarkController controller =
			new ReservationHistoryInsertBenchmarkController(service, guard);
		ReservationHistoryInsertBenchmarkRequest request =
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 10);
		ReservationHistoryInsertBenchmarkResponse expected = mock(ReservationHistoryInsertBenchmarkResponse.class);
		given(service.run(request)).willReturn(expected);

		var response = controller.run(request, "dedicated-token");

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).isSameAs(expected);
		var ordered = inOrder(guard, service);
		ordered.verify(guard).verify("dedicated-token");
		ordered.verify(service).run(request);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(ReservationHistoryInsertBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		ReservationHistoryInsertBenchmarkService reservationHistoryInsertBenchmarkService() {
			return mock(ReservationHistoryInsertBenchmarkService.class);
		}

		@Bean
		BulkWriteBenchmarkAccessGuard bulkWriteBenchmarkAccessGuard() {
			return mock(BulkWriteBenchmarkAccessGuard.class);
		}
	}
}
