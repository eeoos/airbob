package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.messaging.outbox.OutboxWriter;

@Service
public class PaymentOperationRecoveryService {
	private final PaymentOperationRepository repository;
	private final OutboxWriter outboxWriter;
	private final PaymentOperationProperties properties;
	private final Clock clock;

	public PaymentOperationRecoveryService(
		PaymentOperationRepository repository,
		OutboxWriter outboxWriter,
		PaymentOperationProperties properties,
		Clock clock
	) {
		this.repository = repository;
		this.outboxWriter = outboxWriter;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public RecoveryBatch recoverDue() {
		Instant now = clock.instant();
		List<PaymentOperation> recoverable = repository.findRecoverableForUpdate(now, properties.batchSize());
		int enqueued = 0;

		for (PaymentOperation operation : recoverable) {
			if (!operation.prepareRecoveryDispatch(now)) {
				continue;
			}
			outboxWriter.append(new PaymentOperationExecutionRequestedV1(
				operation.getOperationUid(),
				operation.getReservation().getReservationUid(),
				operation.getDispatchGeneration()
		));
			enqueued++;
		}

		return new RecoveryBatch(enqueued);
	}

	public record RecoveryBatch(int enqueued) {
	}
}
