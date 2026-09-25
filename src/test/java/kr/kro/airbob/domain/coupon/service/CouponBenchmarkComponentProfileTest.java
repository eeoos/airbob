package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@DisplayName("쿠폰 DB 벤치마크 빈 프로필 테스트")
class CouponBenchmarkComponentProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("정상 프로필에서는 쿠폰 DB 발급 빈을 만들지 않는다")
	void normalProfileExcludesDBCouponBeans() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(CouponDbIssueService.class));
	}

	@Test
	@DisplayName("coupon benchmark 프로필에서는 쿠폰 DB 발급 빈을 만든다")
	void benchmarkProfileCreatesDBCouponBeans() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.run(context -> assertThat(context)
				.hasSingleBean(CouponDbIssueService.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(CouponDbIssueService.class)
	static class TestConfiguration {

		@Bean
		CouponIssueTransactionService couponIssueTransactionService() {
			return mock(CouponIssueTransactionService.class);
		}

		@Bean
		CouponIssueMetricRecorder couponIssueMetricRecorder() {
			return mock(CouponIssueMetricRecorder.class);
		}
	}
}
