package kr.kro.airbob.domain.statistics.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.service.RevenueStatsBenchmarkService;

@DisplayName("일일 매출 before 벤치마크 API 테스트")
class RevenueStatsBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 before API를 노출하지 않는다")
	void disabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(RevenueStatsBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필 없이 설정만 켜도 before API를 노출하지 않는다")
	void propertyAloneCannotEnableApi() {
		contextRunner
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(RevenueStatsBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필과 설정을 함께 켜면 before API를 노출한다")
	void profileAndPropertyEnableApi() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(RevenueStatsBenchmarkController.class));
	}

	@Test
	@DisplayName("토큰을 확인한 뒤 원장 직접 집계 서비스를 호출한다")
	void delegatesToRawLedgerService() {
		RevenueStatsBenchmarkService service = mock(RevenueStatsBenchmarkService.class);
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		RevenueStatsBenchmarkController controller =
			new RevenueStatsBenchmarkController(service, guard);
		LocalDate from = LocalDate.of(2026, 7, 1);
		LocalDate to = LocalDate.of(2026, 7, 7);
		RevenueStatsResponse.DailyRevenues expected =
			new RevenueStatsResponse.DailyRevenues(from, to, "raw", List.of());
		given(service.getDailyRevenueBefore(from, to)).willReturn(expected);

		var response = controller.getDailyRevenueBefore(from, to, "secret-token");

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		verify(guard).verify("secret-token");
		verify(service).getDailyRevenueBefore(from, to);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(RevenueStatsBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		RevenueStatsBenchmarkService revenueStatsBenchmarkService() {
			return mock(RevenueStatsBenchmarkService.class);
		}

		@Bean
		BenchmarkAccessGuard benchmarkAccessGuard() {
			return mock(BenchmarkAccessGuard.class);
		}
	}
}
