package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CouponTimeProviderTest {

	@ParameterizedTest
	@ValueSource(strings = {"UTC", "Asia/Seoul", "America/Los_Angeles"})
	@DisplayName("공통 Clock의 시간대와 무관하게 쿠폰 비교에는 같은 UTC 시각을 사용한다")
	void readsUtcFromTheInjectedClock(String zone) {
		var clock = Clock.fixed(Instant.parse("2026-07-18T01:00:00.123456Z"), ZoneId.of(zone));
		assertThat(new CouponTimeProvider(clock).now())
			.isEqualTo(LocalDateTime.of(2026, 7, 18, 1, 0, 0, 123456000));
	}
}
