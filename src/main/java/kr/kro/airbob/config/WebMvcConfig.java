package kr.kro.airbob.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
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

		bean.addUrlPatterns("/api/v1/*");

		bean.setOrder(1);
		return bean;
	}

	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilter() {
		CorsConfiguration config = new CorsConfiguration();

		// 자격 증명 허용
		config.setAllowCredentials(true);

		// 허용할 도메인 명시
		config.setAllowedOrigins(List.of(
			"http://localhost:3000",          // 로컬 개발용
			"https://www.airbob.cloud",       // 운영 메인
			"https://airbob.cloud"            // 운영 루트
		));

		// 허용할 HTTP 메서드
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

		// 허용할 헤더
		config.setAllowedHeaders(List.of("*"));

		// 브라우저가 preflight 결과를 캐싱할 시간 (1시간)
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);

		FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));

		// 인증 필터보다 먼저 실행되어 CORS 헤더를 발급
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE);

		return bean;
	}
}
