package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;

@DisplayName("예약 V2 세션 인증 경계 테스트")
class WebMvcConfigReservationProtectionTest {

	@Test
	@DisplayName("견적과 checkout 엔드포인트에 세션 인증 필터를 적용한다")
	void reservationV2EndpointsUseSessionAuthentication() {
		WebMvcConfig config = new WebMvcConfig(
			mock(CurrentMemberIdArgumentResolver.class),
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);

		assertThat(config.sessionFilter().getUrlPatterns())
			.contains("/api/v2/reservation-quotes", "/api/v2/reservations");
	}
}
