package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.OutboxWriter;

@ExtendWith(MockitoExtension.class)
class PaymentOperationCommandServiceTest {

	private static final UUID RESERVATION_UID = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
	private static final UUID EXISTING_OPERATION_UID = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
	private static final Long GUEST_ID = 10L;
	private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");

	@Mock private ReservationRepository reservationRepository;
	@Mock private PaymentOperationRepository paymentOperationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private OutboxWriter outboxWriter;

	private PaymentOperationCommandService service;
	private Reservation pendingReservation;

	@BeforeEach
	void setUp() {
		service = new PaymentOperationCommandService(
			reservationRepository,
			paymentOperationRepository,
			historyRepository,
			outboxWriter,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		pendingReservation = Reservation.builder()
			.id(1L)
			.reservationUid(RESERVATION_UID)
			.guest(Member.builder().id(GUEST_ID).build())
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(NOW.plusSeconds(60))
			.build();
	}

	@Test
	void ownerCreatesOperationAndCommandInOneTransaction() {
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(pendingReservation));
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());
		ArgumentCaptor<PaymentOperation> operationCaptor = ArgumentCaptor.forClass(PaymentOperation.class);
		ArgumentCaptor<PaymentOperationExecutionRequestedV1> eventCaptor =
			ArgumentCaptor.forClass(PaymentOperationExecutionRequestedV1.class);

		Accepted accepted = service.requestConfirmation(request(), GUEST_ID);

		assertThat(accepted.status()).isEqualTo(Status.PENDING);
		assertThat(accepted.statusUrl()).isEqualTo("/api/v1/payment-operations/" + accepted.operationId());
		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		then(paymentOperationRepository).should().save(operationCaptor.capture());
		assertThat(operationCaptor.getValue().getOperationUid()).isEqualTo(accepted.operationId());
		then(outboxWriter).should().append(eventCaptor.capture());
		assertThat(eventCaptor.getValue()).isEqualTo(new PaymentOperationExecutionRequestedV1(
			accepted.operationId(), RESERVATION_UID, 1));
		assertThat(eventCaptor.getValue().partitionKey()).isEqualTo(RESERVATION_UID.toString());
		assertThat(eventCaptor.getValue().deduplicationKey())
			.isEqualTo("PAYMENT_EXECUTION:" + accepted.operationId() + ":1");
	}

	@Test
	void nonOwnerCreatesNeitherOperationNorOutbox() {
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(pendingReservation));

		assertThatThrownBy(() -> service.requestConfirmation(request(), 999L))
			.isInstanceOf(PaymentAccessDeniedException.class);

		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(paymentOperationRepository).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void identicalReplayReturnsSameOperationButDifferentRequestConflicts() {
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(pendingReservation));
		PaymentOperation existing = existingOperation("pk-one", 100_000L);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.of(existing));

		assertThat(service.requestConfirmation(request("pk-one", 100_000), GUEST_ID).operationId())
			.isEqualTo(EXISTING_OPERATION_UID);
		assertThatThrownBy(() -> service.requestConfirmation(request("pk-two", 100_000), GUEST_ID))
			.isInstanceOf(PaymentOperationConflictException.class);

		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(paymentOperationRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void expiredReservationCreatesNeitherOperationNorOutbox() {
		pendingReservation = Reservation.builder()
			.id(1L).reservationUid(RESERVATION_UID).guest(Member.builder().id(GUEST_ID).build())
			.totalPrice(100_000L).status(ReservationStatus.PAYMENT_PENDING).expiresAt(NOW).build();
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(pendingReservation));
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.requestConfirmation(request(), GUEST_ID))
			.isInstanceOf(ExpiredReservationConfirmationException.class);

		then(paymentOperationRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void malformedOrderIdDoesNotAccessPersistence() {
		PaymentRequest.Confirm malformed = new PaymentRequest.Confirm("pk", "not-a-uuid", 100_000);

		assertThatThrownBy(() -> service.requestConfirmation(malformed, GUEST_ID))
			.isInstanceOf(InvalidInputException.class);

		then(reservationRepository).shouldHaveNoInteractions();
		then(paymentOperationRepository).shouldHaveNoInteractions();
	}

	private PaymentRequest.Confirm request() {
		return request("pk-one", 100_000);
	}

	private PaymentRequest.Confirm request(String paymentKey, int amount) {
		return new PaymentRequest.Confirm(paymentKey, RESERVATION_UID.toString(), amount);
	}

	private PaymentOperation existingOperation(String paymentKey, long amount) {
		return PaymentOperation.builder()
			.id(2L).operationUid(EXISTING_OPERATION_UID).reservation(pendingReservation).requesterMemberId(GUEST_ID)
			.status(kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.QUEUED)
			.paymentKey(paymentKey).expectedAmount(amount).deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.build();
	}
}
