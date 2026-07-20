package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.coupon.repository.CouponRepository;

@DisplayName("쿠폰 Lua 전환 배포 가드 프로필 테스트")
class CouponLegacyIssuanceRolloutGuardProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(CouponRepository.class, () -> mock(CouponRepository.class))
		.withUserConfiguration(RolloutGuardConfiguration.class);

	@ParameterizedTest(name = "{0} 프로필")
	@ValueSource(strings = {"aws", "oci"})
	@DisplayName("AWS와 OCI 프로필에서만 배포 가드를 생성한다")
	void createsGuardForDeploymentProfiles(String profile) {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.run(context -> assertThat(context)
				.hasSingleBean(CouponLegacyIssuanceRolloutGuard.class));
	}

	@ParameterizedTest(name = "{0} 프로필")
	@ValueSource(strings = {
		"dev", "test", "coupon-benchmark", "nplus1-benchmark", "read-model-benchmark"
	})
	@DisplayName("개발·테스트·벤치마크 프로필에는 배포 가드를 만들지 않는다")
	void excludesGuardFromNonDeploymentProfiles(String profile) {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.run(context -> assertThat(context)
				.doesNotHaveBean(CouponLegacyIssuanceRolloutGuard.class));
	}

	@ParameterizedTest(name = "aws + {0} 프로필")
	@ValueSource(strings = {
		"coupon-benchmark", "nplus1-benchmark", "read-model-benchmark"
	})
	@DisplayName("배포 환경에서도 벤치마크 프로필과 함께면 가드를 만들지 않는다")
	void excludesGuardWhenDeploymentAndBenchmarkProfilesAreCombined(String benchmarkProfile) {
		contextRunner
			.withInitializer(context -> context.getEnvironment()
				.setActiveProfiles("aws", benchmarkProfile))
			.run(context -> assertThat(context)
				.doesNotHaveBean(CouponLegacyIssuanceRolloutGuard.class));
	}

	@Test
	@DisplayName("활성 프로필이 없는 컨텍스트에는 배포 가드를 만들지 않는다")
	void excludesGuardWithoutActiveProfile() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(CouponLegacyIssuanceRolloutGuard.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(CouponLegacyIssuanceRolloutGuard.class)
	static class RolloutGuardConfiguration {
	}
}
