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

@Service
public class PaymentOperationLeaseService {
	private final PaymentOperationRepository repository;
	private final PaymentOperationProperties properties;
	private final PaymentRetryBackoff backoff;
	private final Clock clock;

	public PaymentOperationLeaseService(
		PaymentOperationRepository repository,
		PaymentOperationProperties properties,
		PaymentRetryBackoff backoff,
		Clock clock
	) {
		this.repository = repository;
		this.properties = properties;
		this.backoff = backoff;
		this.clock = clock;
	}

	@Transactional
	public PaymentOperationClaimResult claim(UUID operationUid, long dispatchGeneration) {
		PaymentOperation operation = lock(operationUid);
		Instant now = clock.instant();
		if (operation.markManualReviewIfAttemptsExhausted(
			dispatchGeneration, properties.maxAttempts(), now)) {
			return PaymentOperationClaimResult.manualReview(manualReviewNotice(operation));
		}
		String owner = UUID.randomUUID().toString();
		return operation.acquireLease(owner, dispatchGeneration, now, properties.leaseDuration())
			.map(mode -> PaymentOperationClaimResult.claimed(
				PaymentExecution.from(operation, owner, mode)))
			.orElseGet(PaymentOperationClaimResult::noAction);
	}

	@Transactional
	public Optional<PaymentOperationManualReviewNotice> scheduleRetry(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			return operation.markManualReview(
				execution.leaseOwner(), execution.dispatchGeneration(), now, code, message)
				? Optional.of(manualReviewNotice(operation))
				: Optional.empty();
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		operation.scheduleRetry(
			execution.leaseOwner(), execution.dispatchGeneration(), retryAt, code, message);
		return Optional.empty();
	}

	@Transactional
	public Optional<PaymentOperationManualReviewNotice> markOutcomeUnknown(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			return operation.markManualReview(
				execution.leaseOwner(), execution.dispatchGeneration(), now, code, message)
				? Optional.of(manualReviewNotice(operation))
				: Optional.empty();
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		operation.markOutcomeUnknown(
			execution.leaseOwner(), execution.dispatchGeneration(), retryAt, code, message);
		return Optional.empty();
	}

	@Transactional
	public Optional<PaymentOperationManualReviewNotice> markManualReview(
		PaymentExecution execution,
		String code,
		String message
	) {
		PaymentOperation operation = lock(execution.operationUid());
		return operation.markManualReview(
			execution.leaseOwner(),
			execution.dispatchGeneration(),
			clock.instant(),
			code,
			message
		) ? Optional.of(manualReviewNotice(operation)) : Optional.empty();
	}

	private PaymentOperationManualReviewNotice manualReviewNotice(PaymentOperation operation) {
		return new PaymentOperationManualReviewNotice(operation.getOperationUid());
	}

	private PaymentOperation lock(UUID operationUid) {
		return repository.findByOperationUidWithLock(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
	}
}
