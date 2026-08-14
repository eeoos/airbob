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
	public Optional<PaymentExecution> claim(UUID operationUid) {
		PaymentOperation operation = lock(operationUid);
		Instant now = clock.instant();
		if (operation.markManualReviewIfAttemptsExhausted(properties.maxAttempts(), now)) {
			return Optional.empty();
		}
		String owner = UUID.randomUUID().toString();
		return operation.acquireLease(owner, now, properties.leaseDuration())
			.map(mode -> PaymentExecution.from(operation, owner, mode));
	}

	@Transactional
	public boolean scheduleRetry(PaymentExecution execution, String code, String message) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			return operation.markManualReview(execution.leaseOwner(), now, code, message);
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		return operation.scheduleRetry(execution.leaseOwner(), retryAt, code, message);
	}

	@Transactional
	public boolean markOutcomeUnknown(PaymentExecution execution, String code, String message) {
		PaymentOperation operation = lock(execution.operationUid());
		Instant now = clock.instant();
		if (operation.getAttemptCount() >= properties.maxAttempts()) {
			return operation.markManualReview(execution.leaseOwner(), now, code, message);
		}
		Instant retryAt = now.plus(backoff.forAttempt(operation.getAttemptCount()));
		return operation.markOutcomeUnknown(execution.leaseOwner(), retryAt, code, message);
	}

	private PaymentOperation lock(UUID operationUid) {
		return repository.findByOperationUidWithLock(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
	}
}
