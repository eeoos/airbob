package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@DisplayName("PaymentCancel 엔티티 테스트")
class PaymentCancelTest {

	private Payment payment;

	@BeforeEach
	void setUp() {
		Reservation reservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.build();

		TossPaymentResponse response = TossPaymentResponse.builder()
			.paymentKey("pk_123")
			.orderId("order_123")
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();

		payment = Payment.create(response, reservation);
	}

	@Nested
	@DisplayName("create 팩토리 메서드 테스트")
	class CreateTest {

		@Test
		@DisplayName("Cancel 응답으로 PaymentCancel 생성 시 모든 필드가 매핑된다")
		void 정상_생성() {
			// given
			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(50_000L)
				.cancelReason("고객 요청에 의한 취소")
				.transactionKey("tx_cancel_123")
				.canceledAt(ZonedDateTime.now())
				.build();

			// when
			PaymentCancel cancel = PaymentCancel.create(cancelData, payment);

			// then
			assertThat(cancel.getCancelAmount()).isEqualTo(50_000L);
			assertThat(cancel.getCancelReason()).isEqualTo("고객 요청에 의한 취소");
			assertThat(cancel.getTransactionKey()).isEqualTo("tx_cancel_123");
			assertThat(cancel.getCanceledAt()).isNotNull();
			assertThat(cancel.getPayment()).isEqualTo(payment);
		}

		@Test
		@DisplayName("전액 취소 시 cancelAmount가 전체 금액과 동일하다")
		void 전액_취소_금액() {
			// given
			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("전액 환불")
				.transactionKey("tx_full_cancel")
				.canceledAt(ZonedDateTime.now())
				.build();

			// when
			PaymentCancel cancel = PaymentCancel.create(cancelData, payment);

			// then
			assertThat(cancel.getCancelAmount()).isEqualTo(100_000L);
		}
	}

	@Nested
	@DisplayName("assignPayment 테스트")
	class AssignPaymentTest {

		@Test
		@DisplayName("assignPayment 호출 시 payment가 설정된다")
		void payment_설정() {
			// given
			PaymentCancel cancel = PaymentCancel.builder()
				.cancelAmount(30_000L)
				.cancelReason("부분 취소")
				.transactionKey("tx_123")
				.canceledAt(java.time.LocalDateTime.now())
				.build();

			// when
			cancel.assignPayment(payment);

			// then
			assertThat(cancel.getPayment()).isEqualTo(payment);
		}
	}
}
