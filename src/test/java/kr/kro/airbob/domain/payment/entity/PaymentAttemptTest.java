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

@DisplayName("PaymentAttempt 엔티티 테스트")
class PaymentAttemptTest {

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

	@Nested
	@DisplayName("create 팩토리 메서드 테스트")
	class CreateTest {

		@Test
		@DisplayName("성공 응답으로 PaymentAttempt 생성 시 모든 필드가 매핑된다")
		void 정상_생성() {
			// given
			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("payment_key_123")
				.orderId("order_123")
				.totalAmount(100_000L)
				.method("카드")
				.status("DONE")
				.approvedAt(ZonedDateTime.now())
				.build();

			// when
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);

			// then
			assertThat(attempt.getPaymentKey()).isEqualTo("payment_key_123");
			assertThat(attempt.getOrderId()).isEqualTo("order_123");
			assertThat(attempt.getAmount()).isEqualTo(100_000L);
			assertThat(attempt.getMethod()).isEqualTo(PaymentMethod.CARD);
			assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.DONE);
			assertThat(attempt.getReservation()).isEqualTo(reservation);
			assertThat(attempt.getFailureCode()).isNull();
			assertThat(attempt.getFailureMessage()).isNull();
		}

		@Test
		@DisplayName("가상계좌 결제 시 가상계좌 정보가 포함된다")
		void 가상계좌_정보_포함() {
			// given
			TossPaymentResponse.VirtualAccount virtualAccount = TossPaymentResponse.VirtualAccount.builder()
				.bankCode("088")
				.accountNumber("123456789")
				.customerName("홍길동")
				.dueDate(ZonedDateTime.now().plusHours(24))
				.build();

			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("payment_key_va")
				.orderId("order_va")
				.totalAmount(100_000L)
				.method("가상계좌")
				.status("WAITING_FOR_DEPOSIT")
				.virtualAccount(virtualAccount)
				.build();

			// when
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);

			// then
			assertThat(attempt.getMethod()).isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);
			assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.WAITING_FOR_DEPOSIT);
			assertThat(attempt.getVirtualBankCode()).isEqualTo("088");
			assertThat(attempt.getVirtualAccountNumber()).isEqualTo("123456789");
			assertThat(attempt.getVirtualCustomerName()).isEqualTo("홍길동");
			assertThat(attempt.getVirtualDueDate()).isNotNull();
		}

		@Test
		@DisplayName("failure 정보가 있으면 failureCode와 failureMessage가 설정된다")
		void failure_정보_설정() {
			// given
			TossPaymentResponse.Failure failure = TossPaymentResponse.Failure.builder()
				.code("REJECT_CARD_PAYMENT")
				.message("잔액 부족")
				.build();

			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("payment_key_fail")
				.orderId("order_fail")
				.totalAmount(100_000L)
				.method("카드")
				.status("ABORTED")
				.failure(failure)
				.build();

			// when
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);

			// then
			assertThat(attempt.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
			assertThat(attempt.getFailureMessage()).isEqualTo("잔액 부족");
		}

		@Test
		@DisplayName("virtualAccount가 null이면 가상계좌 관련 필드가 null이다")
		void virtualAccount_null_처리() {
			// given
			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("payment_key_card")
				.orderId("order_card")
				.totalAmount(100_000L)
				.method("카드")
				.status("DONE")
				.virtualAccount(null)
				.build();

			// when
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);

			// then
			assertThat(attempt.getVirtualBankCode()).isNull();
			assertThat(attempt.getVirtualAccountNumber()).isNull();
			assertThat(attempt.getVirtualCustomerName()).isNull();
			assertThat(attempt.getVirtualDueDate()).isNull();
		}
	}

	@Nested
	@DisplayName("createFailedAttempt 팩토리 메서드 테스트")
	class CreateFailedAttemptTest {

		@Test
		@DisplayName("실패 시도 생성 시 status가 ABORTED로 설정된다")
		void 실패_시도_상태_ABORTED() {
			// when
			PaymentAttempt attempt = PaymentAttempt.createFailedAttempt(
				"payment_key_fail",
				"order_fail",
				100_000L,
				reservation,
				"REJECT_CARD_PAYMENT",
				"잔액 부족으로 결제 실패"
			);

			// then
			assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.ABORTED);
		}

		@Test
		@DisplayName("실패 시도 생성 시 method가 UNKNOWN으로 설정된다")
		void 실패_시도_method_UNKNOWN() {
			// when
			PaymentAttempt attempt = PaymentAttempt.createFailedAttempt(
				"payment_key_fail",
				"order_fail",
				100_000L,
				reservation,
				"INVALID_CARD_NUMBER",
				"카드번호 오류"
			);

			// then
			assertThat(attempt.getMethod()).isEqualTo(PaymentMethod.UNKNOWN);
		}

		@Test
		@DisplayName("실패 시도 생성 시 failureCode와 failureMessage가 저장된다")
		void 실패_정보_저장() {
			// when
			PaymentAttempt attempt = PaymentAttempt.createFailedAttempt(
				"payment_key_fail",
				"order_fail",
				100_000L,
				reservation,
				"EXCEED_MAX_AMOUNT",
				"거래금액 한도 초과"
			);

			// then
			assertThat(attempt.getFailureCode()).isEqualTo("EXCEED_MAX_AMOUNT");
			assertThat(attempt.getFailureMessage()).isEqualTo("거래금액 한도 초과");
		}

		@Test
		@DisplayName("실패 시도 생성 시 기본 필드들이 올바르게 설정된다")
		void 기본_필드_설정() {
			// when
			PaymentAttempt attempt = PaymentAttempt.createFailedAttempt(
				"pk_123",
				"order_456",
				50_000L,
				reservation,
				"ERROR_CODE",
				"에러 메시지"
			);

			// then
			assertThat(attempt.getPaymentKey()).isEqualTo("pk_123");
			assertThat(attempt.getOrderId()).isEqualTo("order_456");
			assertThat(attempt.getAmount()).isEqualTo(50_000L);
			assertThat(attempt.getReservation()).isEqualTo(reservation);
		}
	}
}
