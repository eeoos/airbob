package kr.kro.airbob.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

	private final CursorParamArgumentResolver cursorParamArgumentResolver;
	private final SessionAuthFilter sessionAuthFilter;
	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(cursorParamArgumentResolver);
	}

	@Bean
	public FilterRegistrationBean<SessionAuthFilter> sessionFilter() {
		log.info("sessionFilter");
		FilterRegistrationBean<SessionAuthFilter> bean = new FilterRegistrationBean<>(sessionAuthFilter);
		bean.addUrlPatterns(
			"/api/v1/accommodations",
			"/api/v1/accommodations/*",

			"/api/v1/accommodations/*/reviews",
			"/api/v1/accommodations/*/reviews/*",

			"/api/v1/members/wishlists",
			"/api/v1/members/wishlists/*",

			"/api/v1/members/recently-viewed",
			"/api/v1/members/recently-viewed/*",

			"/api/v1/reservations",
			"/api/v1/reservations/*",

			"/api/v1/host/reservations",
			"/api/v1/host/reservations/*",

			"/api/v1/host/accommodations",
			"/api/v1/host/accommodations/*"
			);
		bean.setOrder(1);
		return bean;
	}
}
