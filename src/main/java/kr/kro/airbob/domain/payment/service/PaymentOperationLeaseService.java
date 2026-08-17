package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;

@Service
public class PaymentOperationLeaseService {
	private final PaymentOperationRepository repository;
	private final PaymentOperationProperties properties;
	private final PaymentRetryBackoff backoff;
	private final Clock clock;
	private final OperatorAlertOutboxPublisher alertPublisher;
	private final PaymentOperationManualResolutionRecorder resolutionRecorder;

	public PaymentOperationLeaseService(
		PaymentOperationRepository repository,
		PaymentOperationProperties properties,
		PaymentRetryBackoff backoff,
		Clock clock,
		OperatorAlertOutboxPublisher alertPublisher,
		PaymentOperationManualResolutionRecorder resolutionRecorder
	) {
		this.repository = repository;
		this.properties = properties;
		this.backoff = backoff;
		this.clock = clock;
		this.alertPublisher = alertPublisher;
		this.resolutionRecorder = resolutionRecorder;
	}

	@Transactional
	public Optional<PaymentExecution> claim(UUID operationUid, long dispatchGeneration) {
		PaymentOperation operation = lock(operationUid);
		Instant now = clock.instant();
		boolean manualReconciliation = operation.isManualReconciliationPending();
		if (operation.markManualReviewIfAttemptsExhausted(
			dispatchGeneration, properties.maxAttempts(), now)) {
			appendReviewTransition(
				operation,
				manualReconciliation,
				PaymentOperationStatus.QUEUED,
				now,
				"RECONCILIATION_ATTEMPTS_EXHAUSTED");
			return Optional.empty();
		}
		String owner = UUID.randomUUID().toString();
		return operation.acquireLease(owner, dispatchGeneration, now, properties.leaseDuration())
			.map(mode -> PaymentExecution.from(operation, owner, mode));
	}

	@Transactional
	public void scheduleRetry(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);
			appendIfMovedToManualReview(
				operation,
				operation.markManualReview(
					execution.leaseOwner(), execution.dispatchGeneration(), now, code, message),
				manualReconciliation,
				now,
				"RECONCILIATION_ATTEMPTS_EXHAUSTED");
			return;
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		operation.scheduleRetry(
			execution.leaseOwner(), execution.dispatchGeneration(), retryAt, code, message);
	}

	@Transactional
	public void markOutcomeUnknown(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);
			appendIfMovedToManualReview(
				operation,
				operation.markManualReview(
					execution.leaseOwner(), execution.dispatchGeneration(), now, code, message),
				manualReconciliation,
				now,
				"RECONCILIATION_ATTEMPTS_EXHAUSTED");
			return;
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		operation.markOutcomeUnknown(
			execution.leaseOwner(), execution.dispatchGeneration(), retryAt, code, message);
	}

	@Transactional
	public void markManualReview(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);
		appendIfMovedToManualReview(
			operation,
			operation.markManualReview(
				execution.leaseOwner(),
				execution.dispatchGeneration(),
				now,
				code,
				message),
			manualReconciliation,
			now,
			"RECONCILIATION_REQUIRES_REVIEW");
	}

	@Transactional
	public void returnManualReconciliationToReview(
		PaymentExecution execution,
		String code,
		String message,
		boolean notPaidEligible
	) {
		if (!execution.manualReconciliation()
			|| !execution.mode().isInquiry()
			|| (notPaidEligible && execution.mode() != PaymentExecutionMode.INQUIRE_CONFIRM)) {
			throw new PaymentOperationInvariantException(
				"manual reconciliation review return does not match its inquiry mode");
		}
		PaymentOperation operation = lock(execution.operationUid());
		if (!isCurrentManualReconciliation(operation, execution)) {
			return;
		}
		Instant now = clock.instant();
		boolean transitioned = operation.returnManualReconciliationToReview(
			execution.leaseOwner(),
			execution.dispatchGeneration(),
			now,
			code,
			message,
			notPaidEligible);
		appendIfMovedToManualReview(
			operation,
			transitioned,
			true,
			now,
			notPaidEligible
				? "PROVIDER_PAYMENT_NOT_FOUND"
				: "RECONCILIATION_RETURNED_TO_REVIEW");
	}

	private void appendIfMovedToManualReview(
		PaymentOperation operation,
		boolean transitioned,
		boolean manualReconciliation,
		Instant now,
		String reason
	) {
		if (transitioned) {
			appendReviewTransition(
				operation,
				manualReconciliation,
				PaymentOperationStatus.EXECUTING,
				now,
				reason);
		}
	}

	private void appendReviewTransition(
		PaymentOperation operation,
		boolean manualReconciliation,
		PaymentOperationStatus previousStatus,
		Instant now,
		String reason
	) {
		if (manualReconciliation) {
			resolutionRecorder.recordSystem(
				operation,
				PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
				reason,
				previousStatus,
				PaymentOperationStatus.MANUAL_REVIEW,
				now);
			return;
		}
		appendManualReviewAlert(operation);
	}

	private boolean isCurrentManualReconciliation(
		PaymentOperation operation,
		PaymentExecution execution
	) {
		return execution.manualReconciliation() && operation.isManualReconciliationPending();
	}

	private void appendManualReviewAlert(PaymentOperation operation) {
		alertPublisher.append(OperatorAlertRequest.paymentManualReview(
			operation.getOperationUid(), operation.getManualReviewCount()));
	}

	private PaymentOperation lock(UUID operationUid) {
		return repository.findByOperationUidWithLock(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
	}
}
