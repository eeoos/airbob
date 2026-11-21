package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationNextAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantViolationException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class PaymentOperationManualReviewCommandServiceTest {

	private static final UUID OPERATION_UID = UUID.fromString("7621f641-6677-4a97-b626-85f3a697a0ae");
	private static final UUID RESERVATION_UID = UUID.fromString("29a4332e-c560-4812-8705-5bc86be00f89");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("e282ab31-ef90-4ced-9810-5d3e1da42709");
	private static final long ACCOMMODATION_ID = 31L;
	private static final Instant NOW = Instant.parse("2026-08-17T02:00:00Z");
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 8, 18);
	private static final LocalDate CHECK_OUT = LocalDate.of(2026, 8, 19);

	@Mock private PaymentOperationRepository operationRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private PaymentRepository paymentRepository;
	@Mock private PaymentTransactionRepository transactionRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock private OutboxWriter outboxWriter;
	@Mock private PaymentOperationManualResolutionRecorder resolutionRecorder;

	private PaymentOperationManualReviewCommandService service;

	@BeforeEach
	void setUp() {
		PaymentOperationManualReviewTransactionService transactionService =
			new PaymentOperationManualReviewTransactionService(
				operationRepository,
				reservationRepository,
				inventoryService,
				paymentRepository,
				transactionRepository,
				couponUsageService,
				historyRepository,
				searchRefreshPublisher,
				outboxWriter,
				resolutionRecorder,
				Clock.fixed(NOW, ZoneOffset.UTC));
		service = new PaymentOperationManualReviewCommandService(
			transactionService, new ImmediatePaymentOperationExecutionFence());
	}

	@Test
	void requestReconciliationQueuesOnlyAnInquiryWithAuditAlertAndExecutionOutbox() {
		PaymentOperation operation = manualReviewOperation(false);
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));

		PaymentOperationManualReviewResult result = service.requestReconciliation(
			OPERATION_UID, 99L, 4L);

		assertThat(result.operationUid()).isEqualTo(OPERATION_UID);
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.getDispatchGeneration()).isEqualTo(8);
		assertThat(operation.getAttemptCount()).isZero();
		assertThat(operation.isManualReconciliationPending()).isTrue();
		then(resolutionRecorder).should().recordAdmin(
			eq(operation),
			eq(99L),
			eq(PaymentOperationResolutionAction.RECONCILIATION_REQUESTED),
			eq("ADMIN_RECONCILIATION_REQUESTED"),
			eq(null),
			eq(PaymentOperationStatus.MANUAL_REVIEW),
			eq(PaymentOperationStatus.QUEUED),
			eq(NOW));
		then(outboxWriter).should().append(new PaymentOperationExecutionRequestedV1(
			OPERATION_UID, RESERVATION_UID, 8));
		then(operationRepository).should().flush();
	}

	@Test
	void staleAdminVersionCannotQueueOrAudit() {
		PaymentOperation operation = manualReviewOperation(false);
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));

		assertThatThrownBy(() -> service.requestReconciliation(OPERATION_UID, 99L, 3L))
			.isInstanceOf(PaymentOperationConflictException.class);

		then(outboxWriter).shouldHaveNoInteractions();
		then(resolutionRecorder).shouldHaveNoInteractions();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
	}

	@Test
	void nonManualReviewStateIsARequestConflictNotAnInternalInvariantFailure() {
		PaymentOperation operation = manualReviewOperation(false);
		operation.requestManualReconciliation(NOW);
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));

		assertThatThrownBy(() -> service.requestReconciliation(OPERATION_UID, 99L, 4L))
			.isExactlyInstanceOf(PaymentOperationConflictException.class);

		then(outboxWriter).shouldHaveNoInteractions();
		then(resolutionRecorder).shouldHaveNoInteractions();
	}

	@Test
	void eligibleConfirmationCanBeMarkedNotPaidWithoutLeakingEvidence() {
		PaymentOperation operation = manualReviewOperation(true);
		Reservation reservation = operation.getReservation();
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));
		given(reservationRepository.findByIdWithLock(reservation.getId()))
			.willReturn(Optional.of(reservation));
		given(paymentRepository.findByReservationIdWithLock(reservation.getId()))
			.willReturn(Optional.empty());
		given(transactionRepository.existsByPaymentOperationId(operation.getId())).willReturn(false);
		String evidenceReference = "support-case/ABC-123";

		service.markNotPaid(
			OPERATION_UID,
			99L,
			4L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			evidenceReference);

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.DECLINED);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		InOrder lockOrder = inOrder(reservationRepository, inventoryService);
		lockOrder.verify(reservationRepository).findByIdWithLock(reservation.getId());
		lockOrder.verify(inventoryService).releaseOccupied(
			ACCOMMODATION_ID,
			CHECK_IN,
			CHECK_OUT,
			reservation.getId());
		ArgumentCaptor<PaymentTransaction> ledger = ArgumentCaptor.forClass(PaymentTransaction.class);
		then(transactionRepository).should().save(ledger.capture());
		assertThat(ledger.getValue().getFailureCode()).isEqualTo("MANUAL_NOT_PAID_RESOLUTION");
		assertThat(ledger.getValue().getFailureMessage())
			.isEqualTo("Payment was verified as not paid.")
			.doesNotContain(evidenceReference);
		ArgumentCaptor<ReservationHistory> history = ArgumentCaptor.forClass(ReservationHistory.class);
		then(historyRepository).should().save(history.capture());
		assertThat(history.getValue().getChangeReason())
			.isEqualTo("결제 미승인 수동 확정")
			.doesNotContain(evidenceReference);
		then(couponUsageService).should().restore(reservation.getId());
		then(searchRefreshPublisher).should().requestRefresh(ACCOMMODATION_UID);
		then(resolutionRecorder).should().recordAdmin(
			eq(operation),
			eq(99L),
			eq(PaymentOperationResolutionAction.MARKED_NOT_PAID),
			eq(PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND.name()),
			eq(evidenceReference),
			eq(PaymentOperationStatus.MANUAL_REVIEW),
			eq(PaymentOperationStatus.DECLINED),
			eq(NOW));
	}

	@Test
	void approvedPaymentBlocksMarkNotPaidBeforeAnyMutation() {
		PaymentOperation operation = manualReviewOperation(true);
		Reservation reservation = operation.getReservation();
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));
		given(reservationRepository.findByIdWithLock(reservation.getId()))
			.willReturn(Optional.of(reservation));
		given(paymentRepository.findByReservationIdWithLock(reservation.getId()))
			.willReturn(Optional.of(mock(Payment.class)));

		assertThatThrownBy(() -> service.markNotPaid(
			OPERATION_UID,
			99L,
			4L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"support-case/ABC-123"))
			.isInstanceOf(PaymentOperationInvariantViolationException.class);

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		then(inventoryService).shouldHaveNoInteractions();
		then(transactionRepository).should(never()).save(any());
		then(resolutionRecorder).shouldHaveNoInteractions();
	}

	@Test
	void terminalLedgerBlocksMarkNotPaidBeforeInventoryRelease() {
		PaymentOperation operation = manualReviewOperation(true);
		Reservation reservation = operation.getReservation();
		given(operationRepository.findByOperationUidWithLock(OPERATION_UID))
			.willReturn(Optional.of(operation));
		given(reservationRepository.findByIdWithLock(reservation.getId()))
			.willReturn(Optional.of(reservation));
		given(paymentRepository.findByReservationIdWithLock(reservation.getId()))
			.willReturn(Optional.empty());
		given(transactionRepository.existsByPaymentOperationId(operation.getId())).willReturn(true);

		assertThatThrownBy(() -> service.markNotPaid(
			OPERATION_UID,
			99L,
			4L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"support-case/ABC-123"))
			.isInstanceOf(PaymentOperationInvariantViolationException.class);

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		then(inventoryService).shouldHaveNoInteractions();
		then(transactionRepository).should(never()).save(any());
		then(resolutionRecorder).shouldHaveNoInteractions();
	}

	@Test
	void directCallerCannotPersistFreeTextAsEvidence() {
		assertThatThrownBy(() -> service.markNotPaid(
			OPERATION_UID,
			99L,
			4L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"https://provider.test/case?id=secret value"))
			.isInstanceOf(PaymentOperationConflictException.class);

		then(operationRepository).shouldHaveNoInteractions();
	}

	@Test
	void directCallerMustProvideAClosedNotPaidReason() {
		assertThatThrownBy(() -> service.markNotPaid(
			OPERATION_UID,
			99L,
			4L,
			null,
			"provider-case/NOT-PAID-42"))
			.isInstanceOf(PaymentOperationConflictException.class);

		then(operationRepository).shouldHaveNoInteractions();
	}

	private PaymentOperation manualReviewOperation(boolean notPaidEligible) {
		Accommodation accommodation = Accommodation.builder()
			.id(ACCOMMODATION_ID)
			.accommodationUid(ACCOMMODATION_UID)
			.build();
		Reservation reservation = Reservation.builder()
			.id(21L)
			.reservationUid(RESERVATION_UID)
			.accommodation(accommodation)
			.checkInDate(CHECK_IN)
			.checkOutDate(CHECK_OUT)
			.status(ReservationStatus.PAYMENT_PROCESSING)
			.totalPrice(100_000L)
			.build();
		return PaymentOperation.builder()
			.id(11L)
			.operationUid(OPERATION_UID)
			.reservation(reservation)
			.operationType(PaymentOperationType.CONFIRM)
			.status(PaymentOperationStatus.MANUAL_REVIEW)
			.nextAction(PaymentOperationNextAction.INQUIRE_CONFIRM)
			.paymentKey("payment-key")
			.expectedAmount(100_000L)
			.providerIdempotencyKey("provider-key")
			.deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.dispatchGeneration(7)
			.attemptCount(5)
			.queuedAt(NOW.minusSeconds(60))
			.reviewRequiredAt(NOW.minusSeconds(30))
			.manualReviewCount(1)
			.notPaidResolutionEligible(notPaidEligible)
			.version(4L)
			.build();
	}

	private static final class ImmediatePaymentOperationExecutionFence
		implements PaymentOperationExecutionFence {

		@Override
		public <T> T execute(UUID operationUid, Supplier<T> action) {
			return action.get();
		}
	}
}
