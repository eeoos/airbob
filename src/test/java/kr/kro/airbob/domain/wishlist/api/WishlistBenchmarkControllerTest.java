package kr.kro.airbob.domain.wishlist.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistBenchmarkService;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@DisplayName("위시리스트 before 벤치마크 API 테스트")
class WishlistBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 before API를 노출하지 않는다")
	void disabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(WishlistBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필 없이 설정만 켜도 before API를 노출하지 않는다")
	void propertyAloneCannotEnableApi() {
		contextRunner
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(WishlistBenchmarkController.class));
	}

	@Test
	@DisplayName("프로필과 설정을 함께 켜면 before API를 노출한다")
	void profileAndPropertyEnableApi() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("read-model-benchmark"))
			.withPropertyValues("benchmark.read-model.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(WishlistBenchmarkController.class));
	}

	@Test
	@DisplayName("토큰과 로그인 회원을 사용해 원시 집계 서비스를 호출한다")
	void delegatesWithAuthenticatedMember() {
		WishlistBenchmarkService service = mock(WishlistBenchmarkService.class);
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		WishlistBenchmarkController controller = new WishlistBenchmarkController(service, mock(WishlistService.class), guard);
		CursorRequest.CursorPageRequest request =
			CursorRequest.CursorPageRequest.builder().size(20).build();
		WishlistResponse.WishlistInfos expected = new WishlistResponse.WishlistInfos(
			List.of(), new CursorResponse.PageInfo(false, null, 0));
		given(service.findWishlistsBefore(request, 7L, 25L)).willReturn(expected);

		var response = controller.findWishlistsBefore(request, 25L, "secret-token", 7L);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		verify(guard).verify("secret-token");
		verify(service).findWishlistsBefore(request, 7L, 25L);
	}

	@Test
	@DisplayName("리뷰 원본 집계는 토큰 검증 후 로그인 회원과 커서를 전달한다")
	void rawReviewSummaryRequiresTokenAndPreservesMember() {
		var service = mock(WishlistService.class);
		var controller = new WishlistBenchmarkController(mock(WishlistBenchmarkService.class), service,
			new BenchmarkAccessGuard("token"));
		var request = CursorRequest.CursorPageRequest.builder().size(20).build();
		assertThatThrownBy(() -> controller.findWishlistAccommodationsBeforeReviewSummary(42L, request, "wrong", 7L))
			.isInstanceOf(kr.kro.airbob.common.exception.BaseException.class);
		verifyNoInteractions(service);
		controller.findWishlistAccommodationsBeforeReviewSummary(42L, request, "token", 7L);
		verify(service).findWishlistAccommodationsBeforeReviewSummary(42L, request, 7L);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(WishlistBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		WishlistBenchmarkService wishlistBenchmarkService() {
			return mock(WishlistBenchmarkService.class);
		}

		@Bean
		WishlistService wishlistService() { return mock(WishlistService.class); }

		@Bean
		BenchmarkAccessGuard benchmarkAccessGuard() {
			return mock(BenchmarkAccessGuard.class);
		}
	}
}
