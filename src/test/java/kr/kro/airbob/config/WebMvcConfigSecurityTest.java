package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;

@DisplayName("MVC 인증 및 인가 설정 테스트")
class WebMvcConfigSecurityTest {

	@Test
	@DisplayName("현재 회원 ID argument resolver를 MVC에 등록한다")
	void registersCurrentMemberIdArgumentResolver() {
		CurrentMemberIdArgumentResolver currentMemberIdArgumentResolver =
			mock(CurrentMemberIdArgumentResolver.class);
		WebMvcConfig config = new WebMvcConfig(
			currentMemberIdArgumentResolver,
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);
		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

		config.addArgumentResolvers(resolvers);

		assertThat(resolvers).contains(currentMemberIdArgumentResolver);
	}

	@Test
	@DisplayName("관리자 API에 세션 인증과 ADMIN 인가를 모두 적용한다")
	void adminPathIsProtected() {
		AdminAuthInterceptor adminAuthInterceptor = mock(AdminAuthInterceptor.class);
		WebMvcConfig config = new WebMvcConfig(
			mock(CurrentMemberIdArgumentResolver.class),
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			adminAuthInterceptor,
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v1/*");

		InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
		config.addInterceptors(registry);
		MappedInterceptor adminMapping = registry.entries().stream()
			.filter(MappedInterceptor.class::isInstance)
			.map(MappedInterceptor.class::cast)
			.filter(mapped -> mapped.getInterceptor() == adminAuthInterceptor)
			.findFirst()
			.orElseThrow();

		assertThat(adminMapping.getIncludePathPatterns())
			.contains("/api/v1/admin/**");
	}

	private static class InspectableInterceptorRegistry extends InterceptorRegistry {
		List<Object> entries() {
			return getInterceptors();
		}
	}
}
