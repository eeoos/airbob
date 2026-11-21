package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@DisplayName("Payment 엔티티 테스트")
class PaymentTest {

	private static final Instant APPROVED_AT = Instant.parse("2026-08-12T05:30:00.123456Z");
	private Reservation reservation;

	@BeforeEach
	void setUp() {
		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();
	}

	@Test
	void normalizedConfirmationCreatesTheCurrentPaymentState() {
		ConfirmedPayment confirmed = new ConfirmedPayment(
			"test_payment_key_123",
			reservation.getReservationUid().toString(),
			100_000L,
			100_000L,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			APPROVED_AT,
			null
		);

		Payment payment = Payment.create(confirmed, reservation);

		assertThat(payment)
			.extracting(
				Payment::getPaymentKey,
				Payment::getOrderId,
				Payment::getAmount,
				Payment::getBalanceAmount,
				Payment::getMethod,
				Payment::getStatus,
				Payment::getApprovedAt,
				Payment::getReservation
			)
			.containsExactly(
				"test_payment_key_123",
				reservation.getReservationUid().toString(),
				100_000L,
				100_000L,
				PaymentMethod.CARD,
				PaymentStatus.DONE,
				APPROVED_AT,
				reservation
			);
	}

	@Test
	void normalizedFullCancellationUpdatesOnlyTheCurrentPaymentState() {
		Payment payment = Payment.create(new ConfirmedPayment(
			"test_payment_key_123",
			reservation.getReservationUid().toString(),
			100_000L,
			100_000L,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			APPROVED_AT,
			null
		), reservation);
		CancelledPayment cancelled = new CancelledPayment(
			payment.getPaymentKey(),
			payment.getOrderId(),
			100_000L,
			0L,
			PaymentStatus.CANCELED,
			100_000L,
			"사용자 요청",
			"cancel-transaction-key",
			APPROVED_AT.plusSeconds(60)
		);

		payment.applyFullCancellation(cancelled);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getBalanceAmount()).isZero();
	}
}
