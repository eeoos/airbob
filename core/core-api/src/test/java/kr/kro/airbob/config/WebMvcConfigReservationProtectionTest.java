package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.api.security.AdminAuthInterceptor;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.api.security.SessionAuthFilter;
import kr.kro.airbob.core.support.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.core.support.monitoring.QueryCountInterceptor;

@DisplayName("예약 세션 인증 경계 테스트")
class WebMvcConfigReservationProtectionTest {

	@Test
	@DisplayName("견적과 checkout 엔드포인트에 세션 인증 필터를 적용한다")
	void reservationEndpointsUseSessionAuthentication() {
		WebMvcConfig config = new WebMvcConfig(
			mock(CurrentMemberIdArgumentResolver.class),
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v1/*")
			.doesNotContain("/api/v2/reservation-quotes", "/api/v2/reservations", "/api/v2/reservations/*");
	}
}
