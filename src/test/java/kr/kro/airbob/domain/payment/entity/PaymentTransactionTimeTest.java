package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@DisplayName("결제 거래 시각 테스트")
class PaymentTransactionTimeTest {

	@Test
	@DisplayName("PG 승인 메타데이터의 가상계좌 시각은 원장에 보존한다")
	void virtualAccountDueDatePreservesOffset() {
		Reservation reservation = reservation();
		ZonedDateTime dueDate = ZonedDateTime.of(
			2026, 8, 13, 23, 30, 0, 123_456_000,
			ZoneId.of("Asia/Seoul")
		);
		ConfirmedPayment confirmed = new ConfirmedPayment(
			"payment-key", reservation.getReservationUid().toString(), 100_000L, 100_000L,
			PaymentMethod.VIRTUAL_ACCOUNT, PaymentStatus.DONE, Instant.parse("2026-08-12T00:00:00Z"),
			new ConfirmedPayment.VirtualAccountDetails("04", "synthetic-account", "guest", dueDate.toInstant()));
		PaymentTransaction transaction = PaymentTransaction.confirm(
			confirmed, reservation, Payment.builder().id(2L).build(), 3L);

		assertThat(transaction.getVirtualDueDate())
			.isEqualTo(Instant.parse("2026-08-13T14:30:00.123456Z"));
	}

	@Test
	@DisplayName("PG 취소 시각은 offset을 보존한 절대 시각으로 저장한다")
	void cancellationTimePreservesOffset() {
		Reservation reservation = reservation();
		Payment payment = Payment.builder()
			.id(2L)
			.paymentKey("payment-key")
			.orderId(reservation.getReservationUid().toString())
			.method(PaymentMethod.CARD)
			.status(PaymentStatus.DONE)
			.reservation(reservation)
			.build();
		ZonedDateTime canceledAt = ZonedDateTime.of(
			2026, 11, 1, 1, 30, 0, 654_321_000,
			ZoneId.of("America/New_York")
		);
		CancelledPayment cancel = new CancelledPayment(
			"payment-key",
			reservation.getReservationUid().toString(),
			100_000L,
			0L,
			PaymentStatus.CANCELED,
			100_000L,
			"사용자 요청",
			"cancel-transaction-key",
			canceledAt.toInstant()
		);

		PaymentTransaction transaction = PaymentTransaction.cancel(
			cancel, reservation, payment, 3L);

		assertThat(transaction.getCanceledAt()).isEqualTo(canceledAt.toInstant());
	}

	private Reservation reservation() {
		return Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.build();
	}
}
