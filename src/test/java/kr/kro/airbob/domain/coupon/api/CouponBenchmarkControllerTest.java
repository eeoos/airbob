package kr.kro.airbob.domain.coupon.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;

@DisplayName("Redisson 쿠폰 벤치마크 API 테스트")
class CouponBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 벤치마크 API를 노출하지 않는다")
	void disabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(CouponBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필 없이 설정만 켜도 벤치마크 API를 노출하지 않는다")
	void propertyAloneCannotEnableApi() {
		contextRunner.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(CouponBenchmarkController.class));
	}

	@Test
	@DisplayName("설정 없이 프로필만 켜도 벤치마크 API를 노출하지 않는다")
	void profileAloneCannotEnableApi() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(CouponBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필과 설정을 함께 켜면 벤치마크 API를 노출한다")
	void profileAndPropertyEnableApi() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(CouponBenchmarkController.class));
	}

	@Test
	@DisplayName("토큰을 확인한 뒤 로그인 회원으로 Redisson 발급 서비스를 호출한다")
	void verifiesTokenBeforeDelegation() {
		CouponLockIssueService service = mock(CouponLockIssueService.class);
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		CouponBenchmarkController controller = new CouponBenchmarkController(service, guard);
		UserContext.set(new UserInfo(7L));

		try {
			var response = controller.issueCouponWithLock(1L, "secret-token");
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			InOrder order = inOrder(guard, service);
			order.verify(guard).verify("secret-token");
			order.verify(service).issue(1L, 7L);
		} finally {
			UserContext.clear();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Import(CouponBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		CouponLockIssueService couponLockIssueService() {
			return mock(CouponLockIssueService.class);
		}

		@Bean
		BenchmarkAccessGuard benchmarkAccessGuard() {
			return mock(BenchmarkAccessGuard.class);
		}
	}
}
