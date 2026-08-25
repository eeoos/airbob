package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("예약 견적 정책 테스트")
class ReservationQuotePolicyTest {

	@Test
	@DisplayName("기본 견적은 발급 시각부터 정확히 5분 동안 유효하다")
	void defaultQuoteTtlIsFiveMinutes() {
		ReservationQuotePolicy policy = ReservationQuotePolicy.defaultPolicy();
		Instant quotedAt = Instant.parse("2026-08-25T03:00:00Z");

		assertThat(policy.expiresAtFrom(quotedAt))
			.isEqualTo(Instant.parse("2026-08-25T03:05:00Z"));
	}

	@Test
	@DisplayName("견적 유효기간은 0보다 커야 한다")
	void rejectsNonPositiveDuration() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReservationQuotePolicy(Duration.ZERO));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReservationQuotePolicy(Duration.ofSeconds(-1)));
	}
}
