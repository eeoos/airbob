package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
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

	public PaymentOperationLeaseService(
		PaymentOperationRepository repository,
		PaymentOperationProperties properties,
		PaymentRetryBackoff backoff,
		Clock clock,
		OperatorAlertOutboxPublisher alertPublisher
	) {
		this.repository = repository;
		this.properties = properties;
		this.backoff = backoff;
		this.clock = clock;
		this.alertPublisher = alertPublisher;
	}

	@Transactional
	public Optional<PaymentExecution> claim(UUID operationUid, long dispatchGeneration) {
		PaymentOperation operation = lock(operationUid);
		Instant now = clock.instant();
		if (operation.markManualReviewIfAttemptsExhausted(
			dispatchGeneration, properties.maxAttempts(), now)) {
			appendManualReviewAlert(operation);
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
			appendIfMovedToManualReview(operation, operation.markManualReview(
				execution.leaseOwner(), execution.dispatchGeneration(), now, code, message));
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
			appendIfMovedToManualReview(operation, operation.markManualReview(
				execution.leaseOwner(), execution.dispatchGeneration(), now, code, message));
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
		appendIfMovedToManualReview(operation, operation.markManualReview(
			execution.leaseOwner(),
			execution.dispatchGeneration(),
			clock.instant(),
			code,
			message
		));
	}

	private void appendIfMovedToManualReview(PaymentOperation operation, boolean transitioned) {
		if (transitioned) {
			appendManualReviewAlert(operation);
		}
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
