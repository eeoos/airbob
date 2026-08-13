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
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
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
	private PaymentTransactionRepository paymentTransactionRepository;

	@Mock
	private ReservationRepository reservationRepository;

	@Mock
	private OutboxEventPublisher outboxEventPublisher;

	@Captor
	private ArgumentCaptor<PaymentTransaction> transactionCaptor;

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
		@DisplayName("결제 성공 시 거래 원장에 CONFIRM이 기록된다")
		void 거래원장_CONFIRM_기록() {
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.empty());
			// when
			paymentTransactionService.processSuccessfulPayment(tossResponse, reservation);

			// then
			then(paymentTransactionRepository).should().save(transactionCaptor.capture());
			PaymentTransaction savedTransaction = transactionCaptor.getValue();

			assertThat(savedTransaction.getTransactionType()).isEqualTo(PaymentTransactionType.CONFIRM);
			assertThat(savedTransaction.getPaymentKey()).isEqualTo("pk_test_123");
			assertThat(savedTransaction.getOrderId()).isEqualTo(reservationUid.toString());
			assertThat(savedTransaction.getAmount()).isEqualTo(100_000L);
		}

		@Test
		@DisplayName("결제 성공 시 Payment가 저장된다")
		void Payment_저장() {
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.empty());
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
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.empty());
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

		@Test
		@DisplayName("예약과 금액이 다른 승인 응답은 어떤 결제 데이터도 저장하지 않는다")
		void rejectsMismatchedSuccessfulPaymentResponse() {
			TossPaymentResponse mismatched = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(90_000L)
				.balanceAmount(90_000L)
				.method("카드")
				.status("DONE")
				.approvedAt(ZonedDateTime.now())
				.build();
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(reservation));

			assertThatThrownBy(() -> paymentTransactionService.processSuccessfulPayment(
				mismatched, reservation))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PG 승인 응답");

			then(paymentRepository).shouldHaveNoInteractions();
			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 승인 성공 이벤트를 다시 받으면 결제와 완료 이벤트를 중복 생성하지 않는다")
		void duplicateSuccessfulPaymentIsIdempotent() {
			Payment existing = Payment.create(tossResponse, reservation);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(existing));

			paymentTransactionService.processSuccessfulPayment(tossResponse, reservation);

			then(paymentRepository).should(never()).save(any());
			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
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
			then(paymentTransactionRepository).should().save(transactionCaptor.capture());
			PaymentTransaction savedTransaction = transactionCaptor.getValue();

			assertThat(savedTransaction.getTransactionType()).isEqualTo(PaymentTransactionType.FAIL);
			assertThat(savedTransaction.getPaymentKey()).isEqualTo("pk_fail");
			assertThat(savedTransaction.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
			assertThat(savedTransaction.getFailureMessage()).isEqualTo("잔액 부족");
			assertThat(savedTransaction.getStatus()).isEqualTo(PaymentStatus.ABORTED);
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

		@Test
		@DisplayName("결제 실패 이력은 DB 컬럼 길이에 맞게 외부 문자열을 제한한다")
		void 실패_이력_외부문자열_길이제한() {
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"p".repeat(201), reservationUid.toString(), 100_000
			);

			paymentTransactionService.processFailedPayment(
				confirmRequest, reservation, "C".repeat(101), "M".repeat(513)
			);

			then(paymentTransactionRepository).should().save(transactionCaptor.capture());
			PaymentTransaction savedTransaction = transactionCaptor.getValue();
			assertThat(savedTransaction.getPaymentKey()).hasSize(200);
			assertThat(savedTransaction.getFailureCode()).hasSize(100);
			assertThat(savedTransaction.getFailureMessage()).hasSize(512);
		}
	}

	@Nested
	@DisplayName("processSuccessfulCancellation 테스트")
	class ProcessSuccessfulCancellationTest {

		@Test
		@DisplayName("결제 취소 성공 반영 시 결제를 잠금 조회할 수 없으면 실패한다")
		void 결제_잠금조회_실패() {
			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.build();
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.empty());

			assertThatThrownBy(() -> paymentTransactionService.processSuccessfulCancellation(
				reservationUid.toString(), cancelResponse))
				.isInstanceOf(PaymentNotFoundException.class);

			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

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
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();

			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			// when
			paymentTransactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

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
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("PARTIAL_CANCELED")
				.balanceAmount(50_000L)
				.cancels(List.of(cancelData))
				.build();

			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			// when
			paymentTransactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			// then
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(50_000L);
			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_FAILED),
				isA(PaymentEvent.PaymentCancellationFailedEvent.class)
			);
			then(outboxEventPublisher).should(never()).save(
				eq(PAYMENT_CANCELLATION_COMPLETED), any());
		}

		@Test
		@DisplayName("전액 취소 성공 시 예약 취소 완료 전달 이벤트가 발행된다")
		void 전액취소_완료이벤트_발행() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("고객 요청")
				.transactionKey("tx_cancel")
				.canceledAt(ZonedDateTime.now())
				.build();
			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			paymentTransactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_COMPLETED),
				argThat(event -> event instanceof PaymentEvent.PaymentCancellationCompletedEvent completed
					&& completed.reservationUid().equals(reservationUid.toString()))
			);
		}

		@Test
		@DisplayName("같은 전액 취소 성공 결과를 다시 받으면 원장과 완료 이벤트를 중복 생성하지 않는다")
		void 중복_전액취소_멱등() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
				.cancelAmount(100_000L)
				.cancelReason("고객 요청")
				.transactionKey("tx_cancel")
				.canceledAt(ZonedDateTime.now())
				.build();
			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			paymentTransactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);
			paymentTransactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			then(paymentTransactionRepository).should(times(1)).save(any(PaymentTransaction.class));
			then(outboxEventPublisher).should(times(1)).save(
				eq(PAYMENT_CANCELLATION_COMPLETED), any());
		}

		@Test
		@DisplayName("다른 결제의 PG 취소 응답은 Payment와 예약에 반영하지 않는다")
		void rejectsMismatchedCancellationResponse() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.paymentKey("another-payment-key")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.build();
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			assertThatThrownBy(() -> paymentTransactionService.processSuccessfulCancellation(
				reservationUid.toString(), cancelResponse))
				.isInstanceOf(IllegalStateException.class);

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
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
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.status("CANCELED")
				.balanceAmount(0L)
				.cancels(List.of(cancelData))
				.build();

			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			// when
			paymentTransactionService.processCompensationInTx(reservationUid.toString(), cancelResponse);

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
