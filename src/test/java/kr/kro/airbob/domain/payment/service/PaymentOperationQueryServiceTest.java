package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.NextAction;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

@ExtendWith(MockitoExtension.class)
class PaymentOperationQueryServiceTest {

	private static final UUID OPERATION_UID = UUID.fromString("08a051de-b1ea-40ce-bb30-b39f2c9ba094");
	private static final Long OWNER_ID = 10L;
	private static final Instant SERVER_TIME = Instant.parse("2026-08-14T01:03:00Z");
	private static final Instant CHECK_IN_AT = SERVER_TIME.plusSeconds(60 * 60);
	private static final Clock CLOCK = Clock.fixed(SERVER_TIME, ZoneOffset.UTC);

	@Mock private PaymentOperationRepository repository;

	@Test
	void ownerSeesPublicStatusAndNoPaymentKey() {
		PaymentOperation operation = operation(
			PaymentOperationStatus.DECLINED, PaymentOperationType.CONFIRM, "PROVIDER_DECLINED", null);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));

		Detail detail = queryService().find(OPERATION_UID, OWNER_ID);

		assertThat(detail.operationId()).isEqualTo(OPERATION_UID);
		assertThat(detail.status()).isEqualTo(Status.FAILED);
		assertThat(detail.failureCode()).isEqualTo("PROVIDER_DECLINED");
		assertThat(detail.userFailureCode()).isEqualTo("PAYMENT_DECLINED");
		assertThat(detail.updatedAt()).isEqualTo(Instant.parse("2026-08-14T01:02:03Z"));
		assertThat(detail.nextAction()).isEqualTo(NextAction.START_NEW_CHECKOUT);
		assertThat(detail.retryAfterSeconds()).isNull();
		assertThat(detail.userMessage())
			.isEqualTo("결제가 완료되지 않았습니다. 새 견적을 받은 뒤 예약을 다시 진행해 주세요.");
		assertThat(detail.serverTime()).isEqualTo(SERVER_TIME);
	}

	@Test
	void nonOwnerCannotObserveAnOperation() {
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation(
			PaymentOperationStatus.QUEUED, PaymentOperationType.CONFIRM, null, null)));

		assertThatThrownBy(() -> queryService().find(OPERATION_UID, 999L))
			.isInstanceOf(PaymentAccessDeniedException.class);
	}

	@Test
	void unknownOperationReturnsNotFound() {
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> queryService().find(OPERATION_UID, OWNER_ID))
			.isInstanceOf(PaymentOperationNotFoundException.class);
	}

	@Test
	void nonFailureStatusDoesNotExposeFailureCode() {
		PaymentOperation operation = operation(
			PaymentOperationStatus.WAITING_RETRY,
			PaymentOperationType.CONFIRM,
			"transient-internal-detail",
			SERVER_TIME.plusSeconds(5).plusNanos(1)
		);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));

		Detail detail = queryService().find(OPERATION_UID, OWNER_ID);

		assertThat(detail.status()).isEqualTo(Status.PENDING);
		assertThat(detail.failureCode()).isNull();
		assertThat(detail.nextAction()).isEqualTo(NextAction.POLL);
		assertThat(detail.retryAfterSeconds()).isEqualTo(6L);
		assertThat(detail.userMessage()).isEqualTo("결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.");
	}

	@Test
	void retryDelayUsesTheMinimumAtTheExactBoundaryAndWhenOverdue() {
		PaymentOperation due = operation(
			PaymentOperationStatus.WAITING_RETRY,
			PaymentOperationType.CANCEL,
			null,
			SERVER_TIME
		);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(due));

		Detail atBoundary = queryService().find(OPERATION_UID, OWNER_ID);

		PaymentOperation overdue = operation(
			PaymentOperationStatus.WAITING_RETRY,
			PaymentOperationType.CANCEL,
			null,
			SERVER_TIME.minusNanos(1)
		);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(overdue));

		Detail afterBoundary = queryService().find(OPERATION_UID, OWNER_ID);

		assertThat(atBoundary.nextAction()).isEqualTo(NextAction.POLL);
		assertThat(atBoundary.retryAfterSeconds()).isEqualTo(2L);
		assertThat(afterBoundary.nextAction()).isEqualTo(NextAction.POLL);
		assertThat(afterBoundary.retryAfterSeconds()).isEqualTo(2L);
	}

	@Test
	void retryDelayIsCappedAtThirtySeconds() {
		PaymentOperation operation = operation(
			PaymentOperationStatus.WAITING_RETRY,
			PaymentOperationType.CONFIRM,
			null,
			SERVER_TIME.plusSeconds(31)
		);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));

		Detail detail = queryService().find(OPERATION_UID, OWNER_ID);

		assertThat(detail.retryAfterSeconds()).isEqualTo(30L);
	}

	@Test
	void automaticAndTerminalStatusesExposeOnlySafeClientActions() {
		Detail queued = detailFor(PaymentOperationStatus.QUEUED, PaymentOperationType.CONFIRM);
		Detail executing = detailFor(PaymentOperationStatus.EXECUTING, PaymentOperationType.CANCEL);
		Detail applied = detailFor(PaymentOperationStatus.APPLIED, PaymentOperationType.CONFIRM);
		Detail appliedCancellation = detailFor(
			PaymentOperationStatus.APPLIED, PaymentOperationType.CANCEL);
		Detail declinedConfirmation = detailFor(
			PaymentOperationStatus.DECLINED, PaymentOperationType.CONFIRM);
		Detail declinedCancellation = detailFor(
			PaymentOperationStatus.DECLINED, PaymentOperationType.CANCEL);
		Detail manualReview = detailFor(
			PaymentOperationStatus.MANUAL_REVIEW, PaymentOperationType.CONFIRM);

		assertThat(queued.nextAction()).isEqualTo(NextAction.POLL);
		assertThat(queued.retryAfterSeconds()).isEqualTo(2L);
		assertThat(executing.nextAction()).isEqualTo(NextAction.POLL);
		assertThat(executing.retryAfterSeconds()).isEqualTo(2L);
		assertThat(applied.nextAction()).isEqualTo(NextAction.NONE);
		assertThat(applied.retryAfterSeconds()).isNull();
		assertThat(applied.userMessage()).isEqualTo("결제가 완료되어 예약이 확정되었습니다.");
		assertThat(appliedCancellation.userMessage()).isEqualTo("예약 취소가 완료되었습니다.");
		assertThat(executing.userMessage())
			.isEqualTo("예약 취소 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.");
		assertThat(declinedConfirmation.nextAction()).isEqualTo(NextAction.START_NEW_CHECKOUT);
		assertThat(declinedConfirmation.retryAfterSeconds()).isNull();
		assertThat(declinedCancellation.nextAction()).isEqualTo(NextAction.RETRY_CANCELLATION);
		assertThat(declinedCancellation.retryAfterSeconds()).isNull();
		assertThat(manualReview.nextAction()).isEqualTo(NextAction.CONTACT_SUPPORT);
		assertThat(manualReview.retryAfterSeconds()).isEqualTo(30L);
	}

	@Test
	void declinedActionsRespectCurrentReservationStateAndCheckInCutoff() {
		Detail confirmationStillPending = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CONFIRM,
			ReservationStatus.PAYMENT_PENDING,
			CHECK_IN_AT
		);
		Detail confirmationAtCutoff = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CONFIRM,
			ReservationStatus.EXPIRED,
			SERVER_TIME
		);
		Detail confirmationLogicallyExpired = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CONFIRM,
			ReservationStatus.PAYMENT_PENDING,
			CHECK_IN_AT,
			SERVER_TIME
		);
		Detail cancellationStillPending = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CANCEL,
			ReservationStatus.CANCELLATION_PENDING,
			CHECK_IN_AT
		);
		Detail cancellationAlreadyApplied = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CANCEL,
			ReservationStatus.CANCELLED,
			CHECK_IN_AT
		);
		Detail cancellationAtCutoff = detailFor(
			PaymentOperationStatus.DECLINED,
			PaymentOperationType.CANCEL,
			ReservationStatus.CANCELLATION_FAILED,
			SERVER_TIME
		);

		assertThat(confirmationStillPending.nextAction()).isEqualTo(NextAction.NONE);
		assertThat(confirmationAtCutoff.nextAction()).isEqualTo(NextAction.NONE);
		assertThat(confirmationLogicallyExpired.nextAction()).isEqualTo(NextAction.START_NEW_CHECKOUT);
		assertThat(cancellationStillPending.nextAction()).isEqualTo(NextAction.NONE);
		assertThat(cancellationAlreadyApplied.nextAction()).isEqualTo(NextAction.NONE);
		assertThat(cancellationAtCutoff.nextAction()).isEqualTo(NextAction.CONTACT_SUPPORT);
	}

	@Test
	void userFailureCodeUsesOnlyStablePublicValuesWithoutChangingTheLegacyCode() {
		Detail confirmation = detailFor(
			PaymentOperationStatus.DECLINED, PaymentOperationType.CONFIRM);
		Detail cancellation = detailFor(
			PaymentOperationStatus.DECLINED, PaymentOperationType.CANCEL);
		Detail review = detailFor(
			PaymentOperationStatus.MANUAL_REVIEW, PaymentOperationType.CONFIRM);
		Detail automatic = detailFor(
			PaymentOperationStatus.WAITING_RETRY, PaymentOperationType.CONFIRM);

		assertThat(confirmation.failureCode()).isEqualTo("raw-provider-code");
		assertThat(confirmation.userFailureCode()).isEqualTo("PAYMENT_DECLINED");
		assertThat(cancellation.userFailureCode()).isEqualTo("PAYMENT_CANCELLATION_DECLINED");
		assertThat(review.userFailureCode()).isEqualTo("PAYMENT_REVIEW_REQUIRED");
		assertThat(automatic.userFailureCode()).isNull();
	}

	private Detail detailFor(PaymentOperationStatus status, PaymentOperationType operationType) {
		PaymentOperation operation = operation(status, operationType, "raw-provider-code", null);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));
		return queryService().find(OPERATION_UID, OWNER_ID);
	}

	private Detail detailFor(
		PaymentOperationStatus status,
		PaymentOperationType operationType,
		ReservationStatus reservationStatus,
		Instant checkInAt
	) {
		return detailFor(
			status,
			operationType,
			reservationStatus,
			checkInAt,
			defaultReservationExpiry(reservationStatus)
		);
	}

	private Detail detailFor(
		PaymentOperationStatus status,
		PaymentOperationType operationType,
		ReservationStatus reservationStatus,
		Instant checkInAt,
		Instant expiresAt
	) {
		PaymentOperation operation = operation(
			status,
			operationType,
			"raw-provider-code",
			null,
			reservationStatus,
			checkInAt,
			expiresAt
		);
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));
		return queryService().find(OPERATION_UID, OWNER_ID);
	}

	private PaymentOperationQueryService queryService() {
		return new PaymentOperationQueryService(repository, CLOCK);
	}

	private PaymentOperation operation(
		PaymentOperationStatus status,
		PaymentOperationType operationType,
		String failureCode,
		Instant nextAttemptAt
	) {
		return operation(
			status,
			operationType,
			failureCode,
			nextAttemptAt,
			defaultReservationStatus(status, operationType),
			CHECK_IN_AT,
			SERVER_TIME
		);
	}

	private PaymentOperation operation(
		PaymentOperationStatus status,
		PaymentOperationType operationType,
		String failureCode,
		Instant nextAttemptAt,
		ReservationStatus reservationStatus,
		Instant checkInAt,
		Instant expiresAt
	) {
		return PaymentOperation.builder()
			.id(1L).operationUid(OPERATION_UID).requesterMemberId(OWNER_ID)
			.operationType(operationType).status(status)
			.reservation(Reservation.builder()
				.reservationUid(UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf"))
				.status(reservationStatus)
				.checkInAt(checkInAt)
				.expiresAt(expiresAt)
				.build())
			.paymentKey("never-expose-me")
			.failureCode(failureCode)
			.failureMessage("never expose provider failure details")
			.nextAttemptAt(nextAttemptAt)
			.dispatchGeneration(99)
			.leaseOwner("never-expose-owner")
			.leaseExpiresAt(SERVER_TIME.plusSeconds(30))
			.updatedAt(LocalDateTime.ofInstant(Instant.parse("2026-08-14T01:02:03Z"), ZoneOffset.UTC))
			.build();
	}

	private ReservationStatus defaultReservationStatus(
		PaymentOperationStatus status,
		PaymentOperationType operationType
	) {
		if (status == PaymentOperationStatus.DECLINED) {
			return operationType == PaymentOperationType.CONFIRM
				? ReservationStatus.EXPIRED
				: ReservationStatus.CANCELLATION_FAILED;
		}
		return operationType == PaymentOperationType.CONFIRM
			? ReservationStatus.PAYMENT_PROCESSING
			: ReservationStatus.CANCELLATION_PENDING;
	}

	private Instant defaultReservationExpiry(ReservationStatus status) {
		return status == ReservationStatus.PAYMENT_PENDING
			? SERVER_TIME.plusSeconds(60)
			: SERVER_TIME;
	}
}
