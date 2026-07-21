package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@DisplayName("쿠폰 Redisson 벤치마크 빈 프로필 테스트")
class CouponBenchmarkComponentProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("정상 프로필에서는 쿠폰 Redisson 발급 빈을 만들지 않는다")
	void normalProfileExcludesRedissonCouponBeans() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(CouponLockIssueService.class)
			.doesNotHaveBean(CouponLockManager.class));
	}

	@Test
	@DisplayName("coupon benchmark 프로필에서는 쿠폰 Redisson 발급 빈을 만든다")
	void benchmarkProfileCreatesRedissonCouponBeans() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.run(context -> assertThat(context)
				.hasSingleBean(CouponLockIssueService.class)
				.hasSingleBean(CouponLockManager.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import({CouponLockIssueService.class, CouponLockManager.class})
	static class TestConfiguration {

		@Bean
		CouponIssueTransactionService couponIssueTransactionService() {
			return mock(CouponIssueTransactionService.class);
		}

		@Bean
		CouponIssueMetricRecorder couponIssueMetricRecorder() {
			return mock(CouponIssueMetricRecorder.class);
		}

		@Bean
		RedissonClient redissonClient() {
			return mock(RedissonClient.class);
		}
	}
}
