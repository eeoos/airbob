package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.PaymentCompensationService;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventPayload;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("PG 호출 워커 테스트")
class PaymentGatewayWorkerTest {
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private TossPaymentsAdapter tossPaymentsAdapter;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private PaymentRepository paymentRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private PaymentCompensationService paymentCompensationService;
	@Mock private Acknowledgment acknowledgment;

	private PaymentGatewayWorker worker;

	@BeforeEach
	void setUp() {
		worker = new PaymentGatewayWorker(
			debeziumEventParser,
			tossPaymentsAdapter,
			outboxEventPublisher,
			paymentRepository,
			reservationRepository,
			paymentCompensationService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("예약 금액과 다른 승인 요청은 PG를 호출하기 전에 거부한다")
	void rejectsConfirmRequestWithMismatchedReservationAmount() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 90_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW.plusSeconds(60))
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));

		assertThatThrownBy(() -> worker.processConfirmRequest(request))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("결제 승인 요청");

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CALL_SUCCEEDED), any(EventPayload.class));
	}

	@Test
	@DisplayName("PG 승인 응답이 원 요청과 다르면 성공 이벤트를 발행하지 않는다")
	void rejectsMismatchedConfirmResponse() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW.plusSeconds(60))
			.build();
		TossPaymentResponse response = TossPaymentResponse.builder()
			.paymentKey("another-payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.confirmPayment(
			"payment-key", reservationUid.toString(), 100_000))
			.willReturn(response);

		assertThatThrownBy(() -> worker.processConfirmRequest(request))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PG 승인 응답");

		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CALL_SUCCEEDED), any(EventPayload.class));
	}

	@Test
	@DisplayName("원 요청과 일치하는 PG 승인 결과만 성공 이벤트로 발행한다")
	void publishesCorrelatedConfirmResponse() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW.plusSeconds(60))
			.build();
		TossPaymentResponse response = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.confirmPayment(
			"payment-key", reservationUid.toString(), 100_000))
			.willReturn(response);

		worker.processConfirmRequest(request);

		verify(outboxEventPublisher).save(
			eq(EventType.PG_CALL_SUCCEEDED),
			any(PaymentEvent.PgCallSucceededEvent.class));
	}

	@Test
	@DisplayName("PG 호출 이벤트 처리가 예약 만료 뒤로 지연되면 승인 API를 호출하지 않는다")
	void skipsDelayedConfirmRequestAfterExpiration() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW)
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willThrow(new TossPaymentException(PaymentInquiryErrorCode.NOT_FOUND_PAYMENT));

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(tossPaymentsAdapter).getPaymentByPaymentKey("payment-key");
		verify(outboxEventPublisher).save(
			eq(EventType.PG_CALL_FAILED),
			any(PaymentEvent.PgCallFailedEvent.class));
	}

	@Test
	@DisplayName("만료 뒤 재처리라도 PG에 이미 승인된 결제는 조회 결과로 복구한다")
	void recoversAlreadyApprovedPaymentAfterExpiration() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW)
			.build();
		TossPaymentResponse approvedPayment = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.parse("2026-08-11T23:59:59Z"))
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willReturn(approvedPayment);

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(outboxEventPublisher).save(
			eq(EventType.PG_CALL_SUCCEEDED),
			any(PaymentEvent.PgCallSucceededEvent.class));
		verify(outboxEventPublisher, never()).save(
			eq(EventType.PG_CALL_FAILED),
			any(PaymentEvent.PgCallFailedEvent.class));
	}

	@Test
	@DisplayName("처리 중 만료된 예약의 결제가 이미 환불됐다면 예약 만료 흐름으로 넘긴다")
	void expiresProcessingReservationWhenPaymentWasAlreadyRefunded() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.expiresAt(NOW)
			.build();
		TossPaymentResponse canceledPayment = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(0L)
			.status("CANCELED")
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willReturn(canceledPayment);

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(outboxEventPublisher).save(
			eq(EventType.PG_CALL_FAILED),
			any(PaymentEvent.PgCallFailedEvent.class));
	}

	@Test
	@DisplayName("결제 처리 상태가 아닌 예약은 PG 승인 API를 호출하지 않는다")
	void rejectsReservationThatDidNotClaimPayment() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PENDING)
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));

		assertThatThrownBy(() -> worker.processConfirmRequest(request))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("결제 처리 상태");

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CALL_SUCCEEDED), any(EventPayload.class));
	}

	@Test
	@DisplayName("이미 결제가 확정된 예약의 중복 PG 호출 이벤트는 성공 처리로 간주한다")
	void ignoresDuplicateConfirmRequestAfterReservationConfirmed() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.CONFIRMED)
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(tossPaymentsAdapter, never()).getPaymentByPaymentKey(any());
		verify(outboxEventPublisher, never()).save(any(), any());
	}

	@Test
	@DisplayName("이미 만료된 예약의 PG 호출 이벤트는 기존 승인 여부만 조회한다")
	void recoversDuplicateConfirmRequestAfterReservationExpired() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.EXPIRED)
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willThrow(new TossPaymentException(PaymentInquiryErrorCode.NOT_FOUND_PAYMENT));

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(tossPaymentsAdapter).getPaymentByPaymentKey("payment-key");
		verify(outboxEventPublisher).save(
			eq(EventType.PG_CALL_FAILED),
			any(PaymentEvent.PgCallFailedEvent.class));
	}

	@Test
	@DisplayName("이미 만료되고 전액 환불된 결제의 중복 PG 호출 이벤트는 종료한다")
	void ignoresDuplicateConfirmRequestAfterFullRefund() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.EXPIRED)
			.build();
		TossPaymentResponse canceledPayment = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(0L)
			.status("CANCELED")
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willReturn(canceledPayment);

		worker.processConfirmRequest(request);

		verify(tossPaymentsAdapter, never()).confirmPayment(any(), any(), any());
		verify(outboxEventPublisher, never()).save(any(), any());
	}

	@Test
	@DisplayName("잔액이 남은 부분 취소 결제는 보상 취소를 이어서 수행한다")
	void compensatesDuplicateConfirmRequestWithRemainingBalance() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		Reservation reservation = Reservation.builder()
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(ReservationStatus.EXPIRED)
			.build();
		TossPaymentResponse partiallyCanceledPayment = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(50_000L)
			.status("PARTIAL_CANCELED")
			.build();
		given(reservationRepository.findByReservationUid(reservationUid))
			.willReturn(Optional.of(reservation));
		given(tossPaymentsAdapter.getPaymentByPaymentKey("payment-key"))
			.willReturn(partiallyCanceledPayment);

		worker.processConfirmRequest(request);

		verify(paymentCompensationService).compensate(reservationUid.toString());
		verify(outboxEventPublisher, never()).save(any(), any());
	}

	@Test
	@DisplayName("PG 취소 성공 후 성공 이벤트 저장이 실패하면 취소 실패 이벤트로 바꾸지 않는다")
	void doesNotConvertSuccessfulPgCancellationIntoFailure() {
		String message = "pg-cancel-request";
		UUID reservationUid = UUID.randomUUID();
		PaymentEvent.PaymentCancellationRequestedEvent request =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				reservationUid.toString(), "사용자 요청", null);
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.PG_CANCEL_CALL_REQUESTED, request, Instant.EPOCH);
		Payment payment = mock(Payment.class);
		TossPaymentResponse response = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.status("CANCELED")
			.balanceAmount(0L)
			.build();

		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, PaymentEvent.PaymentCancellationRequestedEvent.class))
			.willReturn(envelope);
		given(paymentRepository.findByReservationReservationUid(reservationUid))
			.willReturn(Optional.of(payment));
		given(payment.getPaymentKey()).willReturn("payment-key");
		given(tossPaymentsAdapter.cancelPayment("payment-key", "사용자 요청", null))
			.willReturn(response);
		doThrow(new RuntimeException("outbox 저장 실패"))
			.when(outboxEventPublisher)
			.save(eq(EventType.PG_CANCEL_CALL_SUCCEEDED), any(EventPayload.class));

		assertThatThrownBy(() -> worker.handlePgCallRequest(message, acknowledgment))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("outbox 저장 실패");

		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CANCEL_CALL_FAILED), any(EventPayload.class));
		verify(acknowledgment, never()).acknowledge();
	}

	@Test
	@DisplayName("결과를 알 수 없는 PG 통신 오류는 실패로 확정하지 않고 Kafka 재시도로 넘긴다")
	void retriesUnknownPgCancellationResult() {
		String message = "pg-cancel-request";
		UUID reservationUid = UUID.randomUUID();
		PaymentEvent.PaymentCancellationRequestedEvent request =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				reservationUid.toString(), "사용자 요청", null);
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.PG_CANCEL_CALL_REQUESTED, request, Instant.EPOCH);
		Payment payment = mock(Payment.class);

		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, PaymentEvent.PaymentCancellationRequestedEvent.class))
			.willReturn(envelope);
		given(paymentRepository.findByReservationReservationUid(reservationUid))
			.willReturn(Optional.of(payment));
		given(payment.getPaymentKey()).willReturn("payment-key");
		given(tossPaymentsAdapter.cancelPayment("payment-key", "사용자 요청", null))
			.willThrow(new RuntimeException("응답 유실"));

		assertThatThrownBy(() -> worker.handlePgCallRequest(message, acknowledgment))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("응답 유실");

		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CANCEL_CALL_FAILED), any(EventPayload.class));
	}
}
