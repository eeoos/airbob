package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@DisplayName("Payment 엔티티 테스트")
class PaymentTest {

	private Reservation reservation;
	private TossPaymentResponse tossResponse;

	@BeforeEach
	void setUp() {
		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();

		tossResponse = TossPaymentResponse.builder()
			.paymentKey("test_payment_key_123")
			.orderId("order_123")
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();
	}

	@Nested
	@DisplayName("create 팩토리 메서드 테스트")
	class CreateTest {

		@Test
		@DisplayName("Toss 응답으로 Payment 생성 시 모든 필드가 정확히 매핑된다")
		void 정상_Payment_생성() {
			// when
			Payment payment = Payment.create(tossResponse, reservation);

			// then
			assertThat(payment.getPaymentKey()).isEqualTo("test_payment_key_123");
			assertThat(payment.getOrderId()).isEqualTo("order_123");
			assertThat(payment.getAmount()).isEqualTo(100_000L);
			assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
			assertThat(payment.getApprovedAt()).isNotNull();
			assertThat(payment.getReservation()).isEqualTo(reservation);
		}

		@Test
		@DisplayName("Payment 생성 시 balanceAmount가 totalAmount와 동일하게 설정된다")
		void balanceAmount_초기값_검증() {
			// when
			Payment payment = Payment.create(tossResponse, reservation);

			// then
			assertThat(payment.getBalanceAmount()).isEqualTo(100_000L);
			assertThat(payment.getBalanceAmount()).isEqualTo(payment.getAmount());
		}

		@Test
		@DisplayName("간편결제 방식으로 Payment 생성")
		void 간편결제_방식_생성() {
			// given
			tossResponse.setMethod("간편결제");

			// when
			Payment payment = Payment.create(tossResponse, reservation);

			// then
			assertThat(payment.getMethod()).isEqualTo(PaymentMethod.EASY_PAY);
		}

		@Test
		@DisplayName("가상계좌 방식으로 Payment 생성")
		void 가상계좌_방식_생성() {
			// given
			tossResponse.setMethod("가상계좌");
			tossResponse.setStatus("WAITING_FOR_DEPOSIT");

			// when
			Payment payment = Payment.create(tossResponse, reservation);

			// then
			assertThat(payment.getMethod()).isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.WAITING_FOR_DEPOSIT);
		}
	}

	@Nested
	@DisplayName("updateOnCancel 테스트")
	class UpdateOnCancelTest {

		@Test
		@DisplayName("부분 취소 시 상태가 PARTIAL_CANCELED로 변경된다")
		void 부분_취소_상태_변경() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(50_000L)
				.cancelReason("고객 요청")
				.transactionKey("cancel_tx_123")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("PARTIAL_CANCELED")
				.balanceAmount(50_000L)
				.cancels(List.of(cancelData))
				.build();

			// when
			payment.updateOnCancel(cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(50_000L);
		}

		@Test
		@DisplayName("전액 취소 시 상태가 CANCELED로 변경되고 balanceAmount가 0이 된다")
		void 전액_취소_상태_변경() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("전액 환불")
				.transactionKey("cancel_tx_456")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();

			// when
			payment.updateOnCancel(cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(0L);
		}

		@Test
		@DisplayName("취소 시 PaymentCancel이 cancels 목록에 추가된다")
		void 취소_목록_추가() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(30_000L)
				.cancelReason("부분 취소")
				.transactionKey("cancel_tx_789")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("PARTIAL_CANCELED")
				.balanceAmount(70_000L)
				.cancels(List.of(cancelData))
				.build();

			// when
			payment.updateOnCancel(cancelResponse);

			// then
			assertThat(payment.getCancels()).hasSize(1);
			assertThat(payment.getCancels().get(0).getCancelAmount()).isEqualTo(30_000L);
		}

		@Test
		@DisplayName("cancels가 null이면 PaymentCancel이 추가되지 않는다")
		void cancels_null_처리() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(null)
				.build();

			// when
			payment.updateOnCancel(cancelResponse);

			// then
			assertThat(payment.getCancels()).isEmpty();
		}

		@Test
		@DisplayName("cancels가 빈 리스트면 PaymentCancel이 추가되지 않는다")
		void cancels_빈리스트_처리() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(new ArrayList<>())
				.build();

			// when
			payment.updateOnCancel(cancelResponse);

			// then
			assertThat(payment.getCancels()).isEmpty();
		}
	}

	@Nested
	@DisplayName("addCancel 테스트")
	class AddCancelTest {

		@Test
		@DisplayName("addCancel 호출 시 양방향 관계가 설정된다")
		void 양방향_관계_설정() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);
			PaymentCancel cancel = PaymentCancel.builder()
				.cancelAmount(50_000L)
				.cancelReason("테스트 취소")
				.transactionKey("tx_123")
				.canceledAt(LocalDateTime.now())
				.build();

			// when
			payment.addCancel(cancel);

			// then
			assertThat(payment.getCancels()).contains(cancel);
			assertThat(cancel.getPayment()).isEqualTo(payment);
		}
	}
}
