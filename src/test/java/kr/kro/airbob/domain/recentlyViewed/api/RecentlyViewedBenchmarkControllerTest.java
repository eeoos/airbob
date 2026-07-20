package kr.kro.airbob.domain.recentlyViewed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.recentlyViewed.dto.RecentlyViewedBenchmarkRequest;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedBenchmarkFixtureService;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;

@DisplayName("최근 본 숙소 N+1 벤치마크 API 설정 테스트")
class RecentlyViewedBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 벤치마크 API를 노출하지 않는다")
	void benchmarkApiIsDisabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(RecentlyViewedBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필 없이 설정만 활성화해도 벤치마크 API를 노출하지 않는다")
	void benchmarkApiCannotBeEnabledWithoutBenchmarkProfile() {
		contextRunner
			.withPropertyValues("benchmark.nplus1.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(RecentlyViewedBenchmarkController.class));
	}

	@Test
	@DisplayName("벤치마크 프로필과 설정을 함께 활성화하면 재현 API를 노출한다")
	void benchmarkApiRequiresProfileAndProperty() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("nplus1-benchmark"))
			.withPropertyValues("benchmark.nplus1.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(RecentlyViewedBenchmarkController.class));
	}

	@Test
	@DisplayName("N+1 측정 프로필은 재현 API를 열고 batch fetch를 비활성화한다")
	void nplus1BenchmarkProfileIsolatesTheAddressNPlusOneBaseline() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"nplus1-benchmark",
			new ClassPathResource("application-nplus1-benchmark.yaml")
		);
		MutablePropertySources propertySources = new MutablePropertySources();
		sources.forEach(propertySources::addLast);
		PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

		assertThat(resolver.getProperty("benchmark.nplus1.enabled", Boolean.class)).isTrue();
		assertThat(resolver.getProperty(
			"spring.jpa.properties.hibernate.default_batch_fetch_size",
			Integer.class
		)).isZero();
		assertThat(resolver.getProperty("benchmark.nplus1.account-email"))
			.isEqualTo("benchmark-nplus1@airbob.cloud");
	}

	@Test
	@DisplayName("fixture 요청은 로그인한 회원의 최근 본 숙소 목록을 교체한다")
	void replaceFixtureUsesAuthenticatedMember() {
		RecentlyViewedService recentlyViewedService = mock(RecentlyViewedService.class);
		RecentlyViewedBenchmarkFixtureService fixtureService = mock(RecentlyViewedBenchmarkFixtureService.class);
		RecentlyViewedBenchmarkController controller =
			new RecentlyViewedBenchmarkController(recentlyViewedService, fixtureService);
		RecentlyViewedBenchmarkRequest.Replace request =
			new RecentlyViewedBenchmarkRequest.Replace(List.of(251L, 252L));
		UserContext.set(new UserInfo(7L, "127.0.0.1", "test"));

		try {
			assertThat(controller.replaceRecentlyViewedFixture(request).getStatusCode().is2xxSuccessful()).isTrue();
			verify(fixtureService).replaceFixture(7L, request.accommodationIds());
		} finally {
			UserContext.clear();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Import(RecentlyViewedBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		RecentlyViewedService recentlyViewedService() {
			return mock(RecentlyViewedService.class);
		}

		@Bean
		RecentlyViewedBenchmarkFixtureService recentlyViewedBenchmarkFixtureService() {
			return mock(RecentlyViewedBenchmarkFixtureService.class);
		}
	}
}
