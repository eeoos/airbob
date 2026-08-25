package kr.kro.airbob.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationPaymentAttemptException;

@DisplayName("Reservation 결제 시도 토큰 테스트")
class ReservationPaymentAttemptTest {

	private static final Instant STARTED_AT = Instant.parse("2026-08-25T03:00:00Z");

	@Test
	@DisplayName("유료 예약은 하나의 결제 시도 토큰을 발급한다")
	void issuesOnePaymentAttempt() {
		Reservation reservation = paidPendingReservation();
		UUID attemptId = UUID.randomUUID();

		reservation.requirePaymentAttempt();
		reservation.issuePaymentAttempt(attemptId, STARTED_AT);

		assertThat(reservation.isPaymentAttemptRequired()).isTrue();
		assertThat(reservation.getPaymentAttemptUid()).isEqualTo(attemptId);
		assertThat(reservation.getPaymentAttemptStartedAt()).isEqualTo(STARTED_AT);
		assertThat(reservation.getPaymentAttemptConsumedAt()).isNull();
	}

	@Test
	@DisplayName("동일 토큰은 소비 후 재실행 검증도 통과하고 소비 시각은 바꾸지 않는다")
	void consumedTokenIsReplayable() {
		Reservation reservation = paidPendingReservation();
		UUID attemptId = UUID.randomUUID();
		Instant consumedAt = STARTED_AT.plusSeconds(3);
		reservation.requirePaymentAttempt();
		reservation.issuePaymentAttempt(attemptId, STARTED_AT);

		assertThat(reservation.consumePaymentAttempt(attemptId, consumedAt)).isTrue();
		reservation.validatePaymentAttempt(attemptId);
		assertThat(reservation.consumePaymentAttempt(attemptId, consumedAt.plusSeconds(10))).isFalse();

		assertThat(reservation.getPaymentAttemptConsumedAt()).isEqualTo(consumedAt);
	}

	@Test
	@DisplayName("토큰이 없거나 다른 결제 시도는 R024로 거절한다")
	void rejectsMissingOrDifferentAttempt() {
		Reservation reservation = paidPendingReservation();
		UUID attemptId = UUID.randomUUID();
		reservation.requirePaymentAttempt();
		reservation.issuePaymentAttempt(attemptId, STARTED_AT);

		assertThatThrownBy(() -> reservation.validatePaymentAttempt(null))
			.isInstanceOf(InvalidReservationPaymentAttemptException.class)
			.extracting(exception -> ((InvalidReservationPaymentAttemptException)exception).getErrorCode())
			.isEqualTo(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_INVALID);
		assertThatThrownBy(() -> reservation.validatePaymentAttempt(UUID.randomUUID()))
			.isInstanceOf(InvalidReservationPaymentAttemptException.class);
	}

	@Test
	@DisplayName("토큰이 발급되지 않은 유료 예약도 결제 승인을 우회할 수 없다")
	void unissuedPaymentAttemptIsRejected() {
		Reservation reservation = paidPendingReservation();

		assertThat(reservation.isPaymentAttemptRequired()).isFalse();
		assertThatThrownBy(() -> reservation.validatePaymentAttempt(null))
			.isInstanceOf(InvalidReservationPaymentAttemptException.class);
		assertThatThrownBy(() -> reservation.consumePaymentAttempt(null, STARTED_AT))
			.isInstanceOf(InvalidReservationPaymentAttemptException.class);
	}

	private Reservation paidPendingReservation() {
		return Reservation.builder()
			.reservationUid(UUID.randomUUID())
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(STARTED_AT.plusSeconds(900))
			.build();
	}
}
