package kr.kro.airbob.domain.wishlist.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkService;

@DisplayName("Wishlist 삭제 벌크 쓰기 벤치마크 API 테스트")
class WishlistDeleteBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("profile과 enabled property 중 하나라도 없으면 API가 노출되지 않는다")
	void requiresProfileAndProperty() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(WishlistDeleteBenchmarkController.class));
		contextRunner
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(WishlistDeleteBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(WishlistDeleteBenchmarkController.class));
	}

	@Test
	@DisplayName("profile과 enabled property를 함께 켜면 API가 노출된다")
	void profileAndPropertyEnableController() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(WishlistDeleteBenchmarkController.class));
	}

	@Test
	@DisplayName("전용 토큰을 먼저 검증하고 로그인 ADMIN 식별자로 오케스트레이터를 호출한다")
	void verifiesTokenAndDelegatesAuthenticatedOwner() {
		WishlistDeleteBenchmarkService service = mock(WishlistDeleteBenchmarkService.class);
		BulkWriteBenchmarkAccessGuard guard = mock(BulkWriteBenchmarkAccessGuard.class);
		WishlistDeleteBenchmarkController controller = new WishlistDeleteBenchmarkController(service, guard);
		WishlistDeleteBenchmarkRequest request = new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 10);
		WishlistDeleteBenchmarkResponse expected = mock(WishlistDeleteBenchmarkResponse.class);
		given(service.run(7L, request)).willReturn(expected);
		var response = controller.run(request, "dedicated-token", 7L);

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).isSameAs(expected);
		var ordered = inOrder(guard, service);
		ordered.verify(guard).verify("dedicated-token");
		ordered.verify(service).run(7L, request);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(WishlistDeleteBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		WishlistDeleteBenchmarkService wishlistDeleteBenchmarkService() {
			return mock(WishlistDeleteBenchmarkService.class);
		}

		@Bean
		BulkWriteBenchmarkAccessGuard bulkWriteBenchmarkAccessGuard() {
			return mock(BulkWriteBenchmarkAccessGuard.class);
		}
	}
}
