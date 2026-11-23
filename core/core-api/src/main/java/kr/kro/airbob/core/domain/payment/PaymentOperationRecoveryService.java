package kr.kro.airbob.core.domain.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.core.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;

@Service
public class PaymentOperationRecoveryService {
	private final PaymentOperationReader operationReader;
	private final OutboxWriter outboxWriter;
	private final PaymentOperationProperties properties;
	private final Clock clock;

	public PaymentOperationRecoveryService(
		PaymentOperationReader operationReader,
		OutboxWriter outboxWriter,
		PaymentOperationProperties properties,
		Clock clock
	) {
		this.operationReader = operationReader;
		this.outboxWriter = outboxWriter;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public RecoveryBatch recoverDue() {
		Instant now = clock.instant();
		List<PaymentOperation> recoverable = operationReader.readRecoverableForUpdate(now, properties.batchSize());
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
