package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.PAYMENT_CANCELLATION_COMPLETED;
import static kr.kro.airbob.outbox.EventType.PAYMENT_CANCELLATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancellationTransactionService 테스트")
class PaymentCancellationTransactionServiceTest {

	@InjectMocks
	private PaymentCancellationTransactionService transactionService;

	@Mock private PaymentRepository paymentRepository;
	@Mock private PaymentTransactionRepository paymentTransactionRepository;
	@Mock private OutboxEventPublisher outboxEventPublisher;

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

			assertThatThrownBy(() -> transactionService.processSuccessfulCancellation(
				reservationUid.toString(), cancelResponse))
				.isInstanceOf(PaymentNotFoundException.class);

			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("취소 성공 시 Payment 상태가 업데이트된다")
		void Payment_상태_업데이트() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse cancelResponse = fullCancellationResponse("tx_cancel");
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			transactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(0L);
		}

		@Test
		@DisplayName("부분 취소 시 Payment 상태가 PARTIAL_CANCELED로 변경된다")
		void 부분_취소_상태_변경() {
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

			transactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
			assertThat(payment.getBalanceAmount()).isEqualTo(50_000L);
			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_FAILED),
				isA(PaymentEvent.PaymentCancellationFailedEvent.class));
			then(outboxEventPublisher).should(never()).save(
				eq(PAYMENT_CANCELLATION_COMPLETED), any());
		}

		@Test
		@DisplayName("전액 취소 성공 시 예약 취소 완료 전달 이벤트가 발행된다")
		void 전액취소_완료이벤트_발행() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse cancelResponse = fullCancellationResponse("tx_cancel");
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			transactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_COMPLETED),
				argThat(event -> event instanceof PaymentEvent.PaymentCancellationCompletedEvent completed
					&& completed.reservationUid().equals(reservationUid.toString())));
		}

		@Test
		@DisplayName("같은 전액 취소 성공 결과를 다시 받으면 원장과 완료 이벤트를 중복 생성하지 않는다")
		void 중복_전액취소_멱등() {
			Payment payment = Payment.create(tossResponse, reservation);
			TossPaymentResponse cancelResponse = fullCancellationResponse("tx_cancel");
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(java.util.Optional.of(payment));

			transactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);
			transactionService.processSuccessfulCancellation(reservationUid.toString(), cancelResponse);

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

			assertThatThrownBy(() -> transactionService.processSuccessfulCancellation(
				reservationUid.toString(), cancelResponse))
				.isInstanceOf(IllegalStateException.class);

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
			then(paymentTransactionRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("processFailedCancellationInTx 테스트")
	class ProcessFailedCancellationInTxTest {

		@Test
		@DisplayName("취소 실패 시 PAYMENT_CANCELLATION_FAILED 이벤트가 발행된다")
		void cancellationFailureEventPublished() {
			String reason = "환불 처리 실패";

			transactionService.processFailedCancellationInTx(reservationUid.toString(), reason);

			then(outboxEventPublisher).should().save(
				eq(PAYMENT_CANCELLATION_FAILED),
				argThat(event -> event instanceof PaymentEvent.PaymentCancellationFailedEvent failed
					&& failed.reservationUid().equals(reservationUid.toString())
					&& failed.reason().equals(reason)));
		}
	}

	private TossPaymentResponse fullCancellationResponse(String transactionKey) {
		TossPaymentResponse.Cancel cancelData = TossPaymentResponse.Cancel.builder()
			.cancelAmount(100_000L)
			.cancelReason("고객 요청")
			.transactionKey(transactionKey)
			.canceledAt(ZonedDateTime.now())
			.build();
		return TossPaymentResponse.builder()
			.paymentKey("pk_test_123")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.status("CANCELED")
			.balanceAmount(0L)
			.cancels(List.of(cancelData))
			.build();
	}
}
