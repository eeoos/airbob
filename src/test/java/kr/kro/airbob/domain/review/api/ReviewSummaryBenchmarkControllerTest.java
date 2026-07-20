package kr.kro.airbob.domain.review.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.service.ReviewSummaryBenchmarkService;

@DisplayName("리뷰 요약 before 벤치마크 API 테스트")
class ReviewSummaryBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 before API를 노출하지 않는다")
	void disabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(ReviewSummaryBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필 없이 설정만 켜도 before API를 노출하지 않는다")
	void propertyAloneCannotEnableApi() {
		contextRunner
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(ReviewSummaryBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필과 설정을 함께 켜면 before API를 노출한다")
	void profileAndPropertyEnableApi() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(ReviewSummaryBenchmarkController.class));
	}

	@Test
	@DisplayName("토큰을 확인한 뒤 review 원본 집계 서비스를 호출한다")
	void delegatesToBeforeServiceAfterTokenCheck() {
		ReviewSummaryBenchmarkService service = mock(ReviewSummaryBenchmarkService.class);
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		ReviewSummaryBenchmarkController controller =
			new ReviewSummaryBenchmarkController(service, guard);
		ReviewResponse.ReviewSummary expected = new ReviewResponse.ReviewSummary(3, new BigDecimal("4.00"));
		given(service.findReviewSummaryBefore(10L)).willReturn(expected);

		var response = controller.findReviewSummaryBefore(10L, "secret-token");

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getBody()).isNotNull();
		verify(guard).verify("secret-token");
		verify(service).findReviewSummaryBefore(10L);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(ReviewSummaryBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		ReviewSummaryBenchmarkService reviewSummaryBenchmarkService() {
			return mock(ReviewSummaryBenchmarkService.class);
		}

		@Bean
		BenchmarkAccessGuard benchmarkAccessGuard() {
			return mock(BenchmarkAccessGuard.class);
		}
	}
}
