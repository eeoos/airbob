package kr.kro.airbob.domain.recentlyViewed.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;

class RecentlyViewedReviewSummaryBenchmarkControllerTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("read-model 프로필과 설정이 모두 켜져야 비교 API를 노출한다")
	void requiresReadModelProfileAndProperty() {
		runner.run(context -> assertThat(context).doesNotHaveBean(RecentlyViewedReviewSummaryBenchmarkController.class));
		runner.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(RecentlyViewedReviewSummaryBenchmarkController.class));
		var profiled = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"));
		profiled.run(context -> assertThat(context).doesNotHaveBean(RecentlyViewedReviewSummaryBenchmarkController.class));
		profiled.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(RecentlyViewedReviewSummaryBenchmarkController.class));
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("nplus1-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(RecentlyViewedReviewSummaryBenchmarkController.class));
	}

	@Test
	@DisplayName("토큰 검증 후 로그인 회원의 최근 본 숙소를 조회한다")
	void rejectsInvalidTokenBeforeQueryAndForwardsMember() {
		var service = mock(RecentlyViewedService.class);
		var controller = new RecentlyViewedReviewSummaryBenchmarkController(service, new BenchmarkAccessGuard("token"));
		assertThatThrownBy(() -> controller.getRecentlyViewedBeforeReviewSummary(null, 7L))
			.isInstanceOf(BaseException.class);
		verifyNoInteractions(service);
		controller.getRecentlyViewedBeforeReviewSummary("token", 7L);
		verify(service).getRecentlyViewedBeforeReviewSummary(7L);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(RecentlyViewedReviewSummaryBenchmarkController.class)
	static class TestConfiguration {
		@Bean RecentlyViewedService recentlyViewedService() { return mock(RecentlyViewedService.class); }
		@Bean BenchmarkAccessGuard benchmarkAccessGuard() { return new BenchmarkAccessGuard("token"); }
	}
}
