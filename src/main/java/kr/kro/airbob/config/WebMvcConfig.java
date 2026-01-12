package kr.kro.airbob.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
			.allowedOrigins(
				"http://localhost:3000",          // 로컬 개발
				"https://www.airbob.cloud",       // 프론트엔드 주소
				"https://airbob.cloud"            // 루트 도메인
			)
			.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
			.allowedHeaders("*")
			.allowCredentials(true)
			.maxAge(3600);
	}

	@Bean
	public FilterRegistrationBean<SessionAuthFilter> sessionFilter() {
		log.info("sessionFilter");
		FilterRegistrationBean<SessionAuthFilter> bean = new FilterRegistrationBean<>(sessionAuthFilter);

		bean.addUrlPatterns("/api/v1/*");

		bean.setOrder(1);
		return bean;
	}
}
