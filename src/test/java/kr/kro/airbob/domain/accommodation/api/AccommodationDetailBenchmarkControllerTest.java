package kr.kro.airbob.domain.accommodation.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationDetailBenchmarkService;

@DisplayName("숙소 상세 before 벤치마크 API 테스트")
class AccommodationDetailBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("프로필과 설정이 모두 활성화된 경우에만 before API를 노출한다")
	void requiresProfileAndProperty() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(AccommodationDetailBenchmarkController.class));
		contextRunner
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(AccommodationDetailBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(AccommodationDetailBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(AccommodationDetailBenchmarkController.class));
	}

	@Test
	@DisplayName("벤치마크 토큰을 검증한 뒤 비캐시 상세 조회를 호출한다")
	void verifiesTokenBeforeDelegation() {
		AccommodationDetailBenchmarkService service = mock(AccommodationDetailBenchmarkService.class);
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		AccommodationDetailBenchmarkController controller =
			new AccommodationDetailBenchmarkController(service, guard);
		AccommodationResponse.DetailInfo expected = mock(AccommodationResponse.DetailInfo.class);
		given(service.findAccommodationBefore(10L, 7L)).willReturn(expected);

		var response = controller.findAccommodationBefore(10L, "benchmark-token", 7L);

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).isSameAs(expected);
		var ordered = inOrder(guard, service);
		ordered.verify(guard).verify("benchmark-token");
		ordered.verify(service).findAccommodationBefore(10L, 7L);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(AccommodationDetailBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		AccommodationDetailBenchmarkService accommodationDetailBenchmarkService() {
			return mock(AccommodationDetailBenchmarkService.class);
		}

		@Bean
		BenchmarkAccessGuard benchmarkAccessGuard() {
			return mock(BenchmarkAccessGuard.class);
		}
	}
}
