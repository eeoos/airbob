package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("예약 hold 정책 테스트")
class ReservationHoldPolicyTest {

	@Test
	@DisplayName("설정된 hold 기간을 예약 생성 시각에 더해 만료 시각을 계산한다")
	void calculatesExpiryFromConfiguredDuration() {
		ReservationHoldPolicy policy = new ReservationHoldPolicy(Duration.ofMinutes(7));
		Instant holdStartedAt = Instant.parse("2026-08-25T03:00:00Z");

		assertThat(policy.expiresAtFrom(holdStartedAt))
			.isEqualTo(Instant.parse("2026-08-25T03:07:00Z"));
	}

	@Test
	@DisplayName("hold 기간은 0보다 커야 한다")
	void rejectsNonPositiveDuration() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReservationHoldPolicy(Duration.ZERO));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReservationHoldPolicy(Duration.ofSeconds(-1)));
	}
}
