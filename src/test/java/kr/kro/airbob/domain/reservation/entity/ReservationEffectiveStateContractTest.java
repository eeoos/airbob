package kr.kro.airbob.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("예약 논리 상태 계약 테스트")
class ReservationEffectiveStateContractTest {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

	@Test
	@DisplayName("PAYMENT_PENDING hold는 만료 경계부터 EXPIRED로 보이되 저장 상태는 바꾸지 않는다")
	void expiredPendingHoldIsExposedAsExpiredWithoutMutation() {
		Reservation reservation = reservation(
			ReservationStatus.PAYMENT_PENDING,
			NOW,
			100_000L
		);

		assertThat(reservation.effectiveStatus(NOW)).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
	}

	@Test
	@DisplayName("미만료 유료 PAYMENT_PENDING hold만 결제를 시작할 수 있다")
	void paymentIsAllowedOnlyForAnUnexpiredPaidHold() {
		Reservation activePaidHold = reservation(
			ReservationStatus.PAYMENT_PENDING,
			NOW.plusSeconds(1),
			100_000L
		);
		Reservation expiredPaidHold = reservation(
			ReservationStatus.PAYMENT_PENDING,
			NOW,
			100_000L
		);
		Reservation complimentaryHold = reservation(
			ReservationStatus.PAYMENT_PENDING,
			NOW.plusSeconds(1),
			0L
		);
		Reservation processing = reservation(
			ReservationStatus.PAYMENT_PROCESSING,
			NOW.minusSeconds(1),
			100_000L
		);

		assertThat(activePaidHold.isPaymentAllowedAt(NOW)).isTrue();
		assertThat(expiredPaidHold.isPaymentAllowedAt(NOW)).isFalse();
		assertThat(complimentaryHold.isPaymentAllowedAt(NOW)).isFalse();
		assertThat(processing.isPaymentAllowedAt(NOW)).isFalse();
	}

	@Test
	@DisplayName("PAYMENT_PROCESSING은 원래 hold 시각이 지나도 논리적으로 만료되지 않는다")
	void processingReservationIgnoresOriginalHoldDeadline() {
		Reservation reservation = reservation(
			ReservationStatus.PAYMENT_PROCESSING,
			NOW.minusSeconds(1),
			100_000L
		);

		assertThat(reservation.effectiveStatus(NOW))
			.isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
	}

	private Reservation reservation(
		ReservationStatus status,
		Instant expiresAt,
		long totalPrice
	) {
		return Reservation.builder()
			.status(status)
			.expiresAt(expiresAt)
			.totalPrice(totalPrice)
			.build();
	}
}
