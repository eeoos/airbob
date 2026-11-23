package kr.kro.airbob.core.api.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import kr.kro.airbob.core.api.security.AdminAuthInterceptor;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.api.security.SessionAuthFilter;
import kr.kro.airbob.core.support.cursor.resolver.CouponCampaignCursorParamArgumentResolver;
import kr.kro.airbob.core.support.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.core.support.cursor.resolver.ReviewCursorParamArgumentResolver;
import kr.kro.airbob.core.support.monitoring.QueryCountInterceptor;

@DisplayName("커서 argument resolver MVC 등록 테스트")
class WebMvcConfigCursorResolverTest {

	@Test
	@DisplayName("모든 커서 argument resolver 빈을 MVC에 등록한다")
	void registersEveryCursorArgumentResolverBean() {
		CurrentMemberIdArgumentResolver memberResolver =
			mock(CurrentMemberIdArgumentResolver.class);
		CursorParamArgumentResolver cursorResolver =
			mock(CursorParamArgumentResolver.class);
		ReviewCursorParamArgumentResolver reviewCursorResolver =
			mock(ReviewCursorParamArgumentResolver.class);
		CouponCampaignCursorParamArgumentResolver campaignCursorResolver =
			mock(CouponCampaignCursorParamArgumentResolver.class);
		WebMvcConfig config = new WebMvcConfig(
			memberResolver,
			List.of(cursorResolver, reviewCursorResolver, campaignCursorResolver),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);
		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

		config.addArgumentResolvers(resolvers);

		assertThat(resolvers)
			.containsExactly(cursorResolver, reviewCursorResolver, campaignCursorResolver, memberResolver);
	}
}
