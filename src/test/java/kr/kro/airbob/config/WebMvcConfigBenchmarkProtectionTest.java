package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;

@DisplayName("읽기 모델 벤치마크 v2 관리자 경로 보호 설정 테스트")
class WebMvcConfigBenchmarkProtectionTest {

	@Test
	@DisplayName("v2 쿠폰 benchmark API에 세션 인증을 적용한다")
	void v2CouponBenchmarkPathUsesSessionAuthentication() {
		WebMvcConfig config = new WebMvcConfig(
			mock(CursorParamArgumentResolver.class),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v2/coupons/*");
	}

	@Test
	@DisplayName("v2 숙소 benchmark API에 세션 인증을 적용한다")
	void v2AccommodationBenchmarkPathUsesSessionAuthentication() {
		WebMvcConfig config = new WebMvcConfig(
			mock(CursorParamArgumentResolver.class),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v2/accommodations/*");
	}

	@Test
	@DisplayName("v2 매출 before API에 세션 인증과 ADMIN 인가를 모두 적용한다")
	void v2AdminBenchmarkPathIsProtected() {
		AdminAuthInterceptor adminAuthInterceptor = mock(AdminAuthInterceptor.class);
		WebMvcConfig config = new WebMvcConfig(
			mock(CursorParamArgumentResolver.class),
			mock(SessionAuthFilter.class),
			adminAuthInterceptor,
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v2/admin/*");

		InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
		config.addInterceptors(registry);
		MappedInterceptor adminMapping = registry.entries().stream()
			.filter(MappedInterceptor.class::isInstance)
			.map(MappedInterceptor.class::cast)
			.filter(mapped -> mapped.getInterceptor() == adminAuthInterceptor)
			.findFirst()
			.orElseThrow();

		assertThat(adminMapping.getIncludePathPatterns())
			.contains("/api/v2/admin/**");
	}

	private static class InspectableInterceptorRegistry extends InterceptorRegistry {
		List<Object> entries() {
			return getInterceptors();
		}
	}
}
