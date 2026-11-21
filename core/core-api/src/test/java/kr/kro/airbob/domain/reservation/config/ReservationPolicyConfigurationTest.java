package kr.kro.airbob.domain.reservation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reservation 정책 설정 테스트")
class ReservationPolicyConfigurationTest {

	private final ReservationPolicyConfiguration configuration =
		new ReservationPolicyConfiguration();

	@Test
	@DisplayName("결제 시도 최소 잔여 시간은 예약 보유 시간보다 짧아야 한다")
	void paymentAttemptWindowMustBeShorterThanHold() {
		assertThatThrownBy(() -> configuration.reservationPaymentAttemptPolicy(
			Duration.ofMinutes(15), Duration.ofMinutes(15)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
