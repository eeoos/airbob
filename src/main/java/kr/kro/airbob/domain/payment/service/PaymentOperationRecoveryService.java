package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@Service
public class PaymentOperationRecoveryService {
	private final PaymentOperationRepository repository;
	private final OutboxEventPublisher outboxEventPublisher;
	private final PaymentOperationProperties properties;
	private final Clock clock;

	public PaymentOperationRecoveryService(
		PaymentOperationRepository repository,
		OutboxEventPublisher outboxEventPublisher,
		PaymentOperationProperties properties,
		Clock clock
	) {
		this.repository = repository;
		this.outboxEventPublisher = outboxEventPublisher;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public RecoveryBatch recoverDue() {
		Instant now = clock.instant();
		Instant staleBefore = now.minus(properties.recoveryPublicationInterval());
		List<PaymentOperation> recoverable = repository.findRecoverableForUpdate(
			now, staleBefore, properties.batchSize());
		List<PaymentOperationManualReviewNotice> manualReviews = new ArrayList<>();
		int enqueued = 0;

		for (PaymentOperation operation : recoverable) {
			operation.recoverExpiredExecution(now);
			if (operation.markManualReviewForRecoveryIfAttemptsExhausted(
				properties.maxAttempts(), now)) {
				manualReviews.add(new PaymentOperationManualReviewNotice(operation.getOperationUid()));
				continue;
			}

			outboxEventPublisher.save(
				EventType.PAYMENT_EXECUTION_REQUESTED_V1,
				new PaymentExecutionRequestedV1(
					operation.getOperationUid(), operation.getReservation().getReservationUid())
			);
			operation.recordEnqueued(now);
			enqueued++;
		}

		return new RecoveryBatch(enqueued, manualReviews);
	}

	public record RecoveryBatch(int enqueued, List<PaymentOperationManualReviewNotice> manualReviews) {
		public RecoveryBatch {
			manualReviews = List.copyOf(manualReviews);
		}
	}
}
