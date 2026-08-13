package kr.kro.airbob.config;


import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;


@Configuration
@EnableJpaAuditing(
	auditorAwareRef = "auditorProvider",
	dateTimeProviderRef = "utcDateTimeProvider"
)
public class JpaAuditingConfig {
	// created_by / updated_by 자동 채움: 인증된 요청이면 member.id, 아니면 비움(NULL).
	@Bean
	public AuditorAware<Long> auditorProvider() {
		return () -> Optional.ofNullable(UserContext.get()).map(UserInfo::id);
	}

	@Bean
	public DateTimeProvider utcDateTimeProvider(Clock clock) {
		return () -> Optional.of(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
	}
}
