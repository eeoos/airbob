package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentTransactionService 테스트")
class PaymentTransactionServiceTest {

	@InjectMocks
	private PaymentTransactionService paymentTransactionService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private PaymentAttemptRepository paymentAttemptRepository;

	@Mock
	private OutboxEventPublisher outboxEventPublisher;

	@Captor
	private ArgumentCaptor<PaymentAttempt> attemptCaptor;

	@Captor
	private ArgumentCaptor<Payment> paymentCaptor;

	private Reservation reservation;
	private TossPaymentResponse tossResponse;
	private UUID reservationUid;

	@BeforeEach
	void setUp() {
		reservationUid = UUID.randomUUID();

		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();

		tossResponse = TossPaymentResponse.builder()
			.paymentKey("pk_test_123")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();
	}

	@Nested
	@DisplayName("processSuccessfulPayment 테스트")
	class ProcessSuccessfulPaymentTest {

		@Test
		@DisplayName("결제 성공 시 PaymentAttempt가 저장된다")
		void PaymentAttempt_저장() {
			// when
			paymentTransactionService.processSuccessfulPayment(tossResponse, reservation);

			// then
			then(paymentAttemptRepository).should().save(attemptCaptor.capture());
			PaymentAttempt savedAttempt = attemptCaptor.getValue();

			assertThat(savedAttempt.getPaymentKey()).isEqualTo("pk_test_123");
			assertThat(savedAttempt.getOrderId()).isEqualTo(reservationUid.toString());
			assertThat(savedAttempt.getAmount()).isEqualTo(100_000L);
		}

		@Test
		@DisplayName("결제 성공 시 Payment가 저장된다")
		void Payment_저장() {
			// when
			paymentTransactionService.processSuccessfulPayment(tossResponse, reservation);

			// then
			then(paymentRepository).should().save(paymentCaptor.capture());
			Payment savedPayment = paymentCaptor.getValue();

			assertThat(savedPayment.getPaymentKey()).isEqualTo("pk_test_123");
			assertThat(savedPayment.getAmount()).isEqualTo(100_000L);
			assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.DONE);
		}

		@Test
		@DisplayName("결제 성공 시 PAYMENT_COMPLETED 이벤트가 발행된다")
		void PAYMENT_COMPLETED_이벤트_발행() {
			// when
			paymentTransactionService.processSuccessfulPayment(tossResponse, reservation);

			// then
			then(outboxEventPublisher).should().save(
				eq(PAYMENT_COMPLETED),
				argThat(event -> {
					PaymentEvent.PaymentCompletedEvent completedEvent = (PaymentEvent.PaymentCompletedEvent) event;
					return completedEvent.reservationUid().equals(reservationUid.toString());
				})
			);
		}
	}

	@Nested
	@DisplayName("processFailedPayment 테스트")
	class ProcessFailedPaymentTest {

		@Test
		@DisplayName("결제 실패 시 실패 PaymentAttempt가 저장된다")
		void 실패_PaymentAttempt_저장() {
			// given
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"pk_fail", reservationUid.toString(), 100_000
			);

			// when
			paymentTransactionService.processFailedPayment(
				confirmRequest, reservation, "REJECT_CARD_PAYMENT", "잔액 부족"
			);

			// then
			then(paymentAttemptRepository).should().save(attemptCaptor.capture());
			PaymentAttempt savedAttempt = attemptCaptor.getValue();

			assertThat(savedAttempt.getPaymentKey()).isEqualTo("pk_fail");
			assertThat(savedAttempt.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
			assertThat(savedAttempt.getFailureMessage()).isEqualTo("잔액 부족");
			assertThat(savedAttempt.getStatus()).isEqualTo(PaymentStatus.ABORTED);
		}

		@Test
		@DisplayName("결제 실패 시 PAYMENT_FAILED 이벤트가 발행된다")
		void PAYMENT_FAILED_이벤트_발행() {
			// given
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"pk_fail", reservationUid.toString(), 100_000
			);

			// when
			paymentTransactionService.processFailedPayment(
				confirmRequest, reservation, "REJECT_CARD_PAYMENT", "잔액 부족"
			);

			// then
			then(outboxEventPublisher).should().save(
				eq(PAYMENT_FAILED),
				argThat(event -> {
					PaymentEvent.PaymentFailedEvent failedEvent = (PaymentEvent.PaymentFailedEvent) event;
					return failedEvent.reservationUid().equals(reservationUid.toString())
						&& failedEvent.reason().equals("잔액 부족");
				})
			);
		}
	}

	@Nested
	@DisplayName("processSuccessfulCancellation 테스트")
	class ProcessSuccessfulCancellationTest {

		@Test
		@DisplayName("취소 성공 시 Payment 상태가 업데이트된다")
		void Payment_상태_업데이트() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("고객 요청")
				.transactionKey("tx_cancel")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();

			// when
			paymentTransactionService.processSuccessfulCancellation(payment, cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(0L);
		}

		@Test
		@DisplayName("부분 취소 시 Payment 상태가 PARTIAL_CANCELED로 변경된다")
		void 부분_취소_상태_변경() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(50_000L)
				.cancelReason("부분 취소")
				.transactionKey("tx_partial")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("PARTIAL_CANCELED")
				.balanceAmount(50_000L)
				.cancels(List.of(cancelData))
				.build();

			// when
			paymentTransactionService.processSuccessfulCancellation(payment, cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(50_000L);
		}
	}

	@Nested
	@DisplayName("processCompensationInTx 테스트")
	class ProcessCompensationInTxTest {

		@Test
		@DisplayName("DLQ 보상 트랜잭션 시 Payment 상태가 업데이트된다")
		void 보상_트랜잭션_상태_업데이트() {
			// given
			Payment payment = Payment.create(tossResponse, reservation);

			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("Saga 보상")
				.transactionKey("tx_compensation")
				.canceledAt(ZonedDateTime.now())
				.build();

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();

			// when
			paymentTransactionService.processCompensationInTx(payment, cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		}
	}

	@Nested
	@DisplayName("processFailedCancellationInTx 테스트")
	class ProcessFailedCancellationInTxTest {

		@Test
		@DisplayName("취소 실패 시 PAYMENT_CANCELLATION_FAILED 이벤트가 발행된다")
		void PAYMENT_CANCELLATION_FAILED_이벤트_발행() {
			// given
			String reason = "환불 처리 실패";

			// when
			paymentTransactionService.processFailedCancellationInTx(reservationUid.toString(), reason);

			// then
			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_FAILED),
				argThat(event -> {
					PaymentEvent.PaymentCancellationFailedEvent failedEvent =
						(PaymentEvent.PaymentCancellationFailedEvent) event;
					return failedEvent.reservationUid().equals(reservationUid.toString())
						&& failedEvent.reason().equals(reason);
				})
			);
		}
	}
}
