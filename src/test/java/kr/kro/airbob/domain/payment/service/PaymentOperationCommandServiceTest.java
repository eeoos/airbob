package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryInvariantViolationException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;

@ExtendWith(MockitoExtension.class)
class PaymentOperationCommandServiceTest {

	private static final UUID RESERVATION_UID = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
	private static final UUID EXISTING_OPERATION_UID = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
	private static final UUID PAYMENT_ATTEMPT_ID = UUID.fromString("b72b0711-c957-44ee-a9ee-19aa2c6d93a5");
	private static final UUID WRONG_PAYMENT_ATTEMPT_ID = UUID.fromString("e5a039bd-af07-4f1c-9495-01d8090106f2");
	private static final Long ACCOMMODATION_ID = 20L;
	private static final Long GUEST_ID = 10L;
	private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 8, 20);
	private static final LocalDate CHECK_OUT = LocalDate.of(2026, 8, 22);

	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private PaymentOperationRepository paymentOperationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private OutboxWriter outboxWriter;

	private PaymentOperationCommandService service;
	private Accommodation accommodation;
	private Reservation pendingReservation;

	@BeforeEach
	void setUp() {
		service = new PaymentOperationCommandService(
			reservationRepository,
			inventoryService,
			paymentOperationRepository,
			historyRepository,
			outboxWriter,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		accommodation = Accommodation.builder().id(ACCOMMODATION_ID).build();
		pendingReservation = Reservation.builder()
			.id(1L)
			.reservationUid(RESERVATION_UID)
			.accommodation(accommodation)
			.guest(Member.builder().id(GUEST_ID).build())
			.checkInDate(CHECK_IN)
			.checkOutDate(CHECK_OUT)
			.totalPrice(100_000L)
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(NOW.plusSeconds(60))
			.build();
	}

	@Test
	void ownerCreatesOperationAndCommandInOneTransaction() {
		givenReservationLocks(pendingReservation);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());
		ArgumentCaptor<PaymentOperation> operationCaptor = ArgumentCaptor.forClass(PaymentOperation.class);
		ArgumentCaptor<PaymentOperationExecutionRequestedV1> eventCaptor =
			ArgumentCaptor.forClass(PaymentOperationExecutionRequestedV1.class);

		PaymentRequest.Confirm request = request();
		assertThat(request.paymentAttemptId()).isNull();

		Accepted accepted = service.requestConfirmation(request, GUEST_ID);

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
		InOrder lockOrder = org.mockito.Mockito.inOrder(reservationRepository, inventoryService);
		lockOrder.verify(reservationRepository).findByReservationUidWithLock(RESERVATION_UID);
		lockOrder.verify(inventoryService).transitionHeldToOccupied(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, pendingReservation.getId(), NOW);
	}

	@Test
	void v2FirstOperationValidatesBeforeReplayLookupAndConsumesAttemptBeforePersistence() {
		requirePaymentAttempt(pendingReservation);
		pendingReservation = org.mockito.Mockito.spy(pendingReservation);
		givenReservationLocks(pendingReservation);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());

		service.requestConfirmation(request(PAYMENT_ATTEMPT_ID), GUEST_ID);

		InOrder order = org.mockito.Mockito.inOrder(
			pendingReservation,
			inventoryService,
			paymentOperationRepository,
			outboxWriter
		);
		order.verify(pendingReservation).validatePaymentAttempt(PAYMENT_ATTEMPT_ID);
		order.verify(paymentOperationRepository).findByDeduplicationKey("CONFIRM:" + RESERVATION_UID);
		order.verify(pendingReservation).startPayment(NOW);
		order.verify(pendingReservation).consumePaymentAttempt(PAYMENT_ATTEMPT_ID, NOW);
		order.verify(inventoryService).transitionHeldToOccupied(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, pendingReservation.getId(), NOW);
		order.verify(paymentOperationRepository).save(org.mockito.ArgumentMatchers.any());
		order.verify(outboxWriter).append(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void v2MissingPaymentAttemptIsRejectedBeforeReplayLookup() {
		requirePaymentAttempt(pendingReservation);
		givenReservationLocks(pendingReservation);

		assertPaymentAttemptRejected(request());

		then(paymentOperationRepository).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void v2WrongPaymentAttemptIsRejectedBeforeReplayLookup() {
		requirePaymentAttempt(pendingReservation);
		givenReservationLocks(pendingReservation);

		assertPaymentAttemptRejected(request(WRONG_PAYMENT_ATTEMPT_ID));

		then(paymentOperationRepository).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void sameConsumedPaymentAttemptCanReplayExistingOperationAfterReservationExpiry() {
		pendingReservation = Reservation.builder()
			.id(1L).reservationUid(RESERVATION_UID).accommodation(accommodation)
			.guest(Member.builder().id(GUEST_ID).build())
			.checkInDate(CHECK_IN).checkOutDate(CHECK_OUT)
			.totalPrice(100_000L).status(ReservationStatus.PAYMENT_PROCESSING).expiresAt(NOW)
			.paymentAttemptRequired(true)
			.paymentAttemptUid(PAYMENT_ATTEMPT_ID)
			.paymentAttemptStartedAt(NOW.minusSeconds(30))
			.paymentAttemptConsumedAt(NOW.minusSeconds(1))
			.build();
		givenReservationLocks(pendingReservation);
		PaymentOperation existing = existingOperation("pk-one", 100_000L);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.of(existing));

		Accepted replay = service.requestConfirmation(request(PAYMENT_ATTEMPT_ID), GUEST_ID);

		assertThat(replay.operationId()).isEqualTo(EXISTING_OPERATION_UID);
		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		then(paymentOperationRepository).should(org.mockito.Mockito.never())
			.save(org.mockito.ArgumentMatchers.any());
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void invalidPaymentAttemptCannotObserveExistingOperation() {
		requirePaymentAttempt(pendingReservation);
		givenReservationLocks(pendingReservation);
		PaymentOperation existing = existingOperation("pk-one", 100_000L);
		org.mockito.Mockito.lenient()
			.when(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.thenReturn(Optional.of(existing));

		assertPaymentAttemptRejected(request(WRONG_PAYMENT_ATTEMPT_ID));

		then(paymentOperationRepository).should(org.mockito.Mockito.never())
			.findByDeduplicationKey(org.mockito.ArgumentMatchers.anyString());
		then(paymentOperationRepository).should(org.mockito.Mockito.never())
			.save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void nonOwnerCreatesNeitherOperationNorOutbox() {
		givenReservationLocks(pendingReservation);

		assertThatThrownBy(() -> service.requestConfirmation(request(), 999L))
			.isInstanceOf(PaymentAccessDeniedException.class);

		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(paymentOperationRepository).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void identicalReplayReturnsSameOperationButDifferentRequestConflicts() {
		givenReservationLocks(pendingReservation);
		PaymentOperation existing = existingOperation("pk-one", 100_000L);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.of(existing));

		assertThat(service.requestConfirmation(request("pk-one", 100_000), GUEST_ID).operationId())
			.isEqualTo(EXISTING_OPERATION_UID);
		assertThatThrownBy(() -> service.requestConfirmation(request("pk-two", 100_000), GUEST_ID))
			.isInstanceOf(PaymentOperationConflictException.class);

		assertThat(pendingReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(paymentOperationRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
		then(inventoryService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void expiredReservationCreatesNeitherOperationNorOutbox() {
		pendingReservation = Reservation.builder()
			.id(1L).reservationUid(RESERVATION_UID).accommodation(accommodation)
			.guest(Member.builder().id(GUEST_ID).build())
			.checkInDate(CHECK_IN).checkOutDate(CHECK_OUT)
			.totalPrice(100_000L).status(ReservationStatus.PAYMENT_PENDING).expiresAt(NOW).build();
		givenReservationLocks(pendingReservation);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.requestConfirmation(request(), GUEST_ID))
			.isInstanceOf(ExpiredReservationConfirmationException.class);

		then(paymentOperationRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void mismatchedInventoryCreatesNeitherOperationNorOutbox() {
		givenReservationLocks(pendingReservation);
		given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
			.willReturn(Optional.empty());
		org.mockito.BDDMockito.willThrow(
			new ReservationInventoryInvariantViolationException("owner mismatch"))
			.given(inventoryService).transitionHeldToOccupied(
				ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, pendingReservation.getId(), NOW);

		assertThatThrownBy(() -> service.requestConfirmation(request(), GUEST_ID))
			.isInstanceOf(ReservationInventoryInvariantViolationException.class);

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
		then(inventoryService).shouldHaveNoInteractions();
		then(paymentOperationRepository).shouldHaveNoInteractions();
	}

	private void givenReservationLocks(Reservation reservation) {
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
	}

	private PaymentRequest.Confirm request() {
		return request("pk-one", 100_000);
	}

	private PaymentRequest.Confirm request(String paymentKey, int amount) {
		return new PaymentRequest.Confirm(paymentKey, RESERVATION_UID.toString(), amount);
	}

	private PaymentRequest.Confirm request(UUID paymentAttemptId) {
		return new PaymentRequest.Confirm("pk-one", RESERVATION_UID.toString(), 100_000, paymentAttemptId);
	}

	private void requirePaymentAttempt(Reservation reservation) {
		reservation.requirePaymentAttempt();
		reservation.issuePaymentAttempt(PAYMENT_ATTEMPT_ID, NOW.minusSeconds(30));
	}

	private void assertPaymentAttemptRejected(PaymentRequest.Confirm request) {
		Throwable thrown = catchThrowable(() -> service.requestConfirmation(request, GUEST_ID));

		assertThat(thrown).isInstanceOf(BaseException.class);
		assertThat(((BaseException)thrown).getErrorCode().getCode()).isEqualTo("R024");
	}

	private PaymentOperation existingOperation(String paymentKey, long amount) {
		return PaymentOperation.builder()
			.id(2L).operationUid(EXISTING_OPERATION_UID).reservation(pendingReservation).requesterMemberId(GUEST_ID)
			.status(kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.QUEUED)
			.paymentKey(paymentKey).expectedAmount(amount).deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.build();
	}
}
