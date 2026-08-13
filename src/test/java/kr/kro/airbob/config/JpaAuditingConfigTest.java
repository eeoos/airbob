package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaAuditingConfigTest {

	@Test
	@DisplayName("주입된 Clock의 시간대와 무관하게 감사 시각을 UTC로 생성한다")
	void createsAuditTimeInUtcRegardlessOfClockZone() {
		Clock seoulClock = Clock.fixed(
			Instant.parse("2026-08-13T07:30:00Z"),
			ZoneId.of("Asia/Seoul")
		);

		LocalDateTime auditTime = (LocalDateTime) new JpaAuditingConfig()
			.utcDateTimeProvider(seoulClock)
			.getNow()
			.orElseThrow();

		assertThat(auditTime).isEqualTo(LocalDateTime.of(2026, 8, 13, 7, 30));
	}
}
