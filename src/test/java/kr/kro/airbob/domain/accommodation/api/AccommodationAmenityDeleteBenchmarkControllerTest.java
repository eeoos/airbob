package kr.kro.airbob.domain.accommodation.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkService;

@DisplayName("AccommodationAmenity 삭제 벤치마크 API 테스트")
class AccommodationAmenityDeleteBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@AfterEach
	void clearUserContext() {
		UserContext.clear();
	}

	@Test
	@DisplayName("profile과 enabled property가 모두 있어야 controller가 등록된다")
	void requiresProfileAndProperty() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(AccommodationAmenityDeleteBenchmarkController.class));
		contextRunner
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.doesNotHaveBean(AccommodationAmenityDeleteBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(AccommodationAmenityDeleteBenchmarkController.class));
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues("benchmark.bulk-write.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(AccommodationAmenityDeleteBenchmarkController.class));
	}

	@Test
	@DisplayName("전용 token을 검증한 뒤 인증된 ADMIN ID만 fixture owner로 전달한다")
	void verifiesTokenAndDelegatesAuthenticatedOwner() {
		AccommodationAmenityDeleteBenchmarkService service =
			mock(AccommodationAmenityDeleteBenchmarkService.class);
		BulkWriteBenchmarkAccessGuard guard = mock(BulkWriteBenchmarkAccessGuard.class);
		AccommodationAmenityDeleteBenchmarkController controller =
			new AccommodationAmenityDeleteBenchmarkController(service, guard);
		AccommodationAmenityDeleteBenchmarkRequest request =
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.FULL_REPLACEMENT,
				30
			);
		AccommodationAmenityDeleteBenchmarkResponse expected =
			mock(AccommodationAmenityDeleteBenchmarkResponse.class);
		given(service.run(7L, request)).willReturn(expected);
		UserContext.set(new UserInfo(7L));

		var response = controller.run(request, "dedicated-token");

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).isSameAs(expected);
		var ordered = inOrder(guard, service);
		ordered.verify(guard).verify("dedicated-token");
		ordered.verify(service).run(7L, request);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(AccommodationAmenityDeleteBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		AccommodationAmenityDeleteBenchmarkService accommodationAmenityDeleteBenchmarkService() {
			return mock(AccommodationAmenityDeleteBenchmarkService.class);
		}

		@Bean
		BulkWriteBenchmarkAccessGuard bulkWriteBenchmarkAccessGuard() {
			return mock(BulkWriteBenchmarkAccessGuard.class);
		}
	}
}
