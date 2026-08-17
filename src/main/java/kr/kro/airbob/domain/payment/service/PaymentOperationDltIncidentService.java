package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;
import kr.kro.airbob.messaging.outbox.OutboxWriter;

@Service
public class PaymentOperationDltIncidentService {

	private final IntegrationEventCodec codec;
	private final PaymentOperationRepository operationRepository;
	private final OutboxWriter outboxWriter;
	private final OperatorAlertOutboxPublisher alertPublisher;
	private final Clock clock;

	public PaymentOperationDltIncidentService(
		IntegrationEventCodec codec,
		PaymentOperationRepository operationRepository,
		OutboxWriter outboxWriter,
		OperatorAlertOutboxPublisher alertPublisher,
		Clock clock
	) {
		this.codec = codec;
		this.operationRepository = operationRepository;
		this.outboxWriter = outboxWriter;
		this.alertPublisher = alertPublisher;
		this.clock = clock;
	}

	@Transactional
	public void record(String message, OperatorAlertSourcePosition sourcePosition) {
		if (!PaymentOperationExecutionRequestedV1.TOPIC.equals(sourcePosition.topic())) {
			throw new IllegalArgumentException("payment DLT source topic must be canonical");
		}
		Optional<PaymentOperationExecutionRequestedV1> decoded = decode(message);
		decoded.ifPresent(this::redispatchQueuedGeneration);
		alertPublisher.append(OperatorAlertRequest.paymentOperationQuarantined(
			decoded.map(PaymentOperationExecutionRequestedV1::operationUid).orElse(null),
			sourcePosition
		));
	}

	private Optional<PaymentOperationExecutionRequestedV1> decode(String message) {
		try {
			return Optional.of(codec.decode(
				message,
				PaymentOperationExecutionRequestedV1.DESCRIPTOR,
				PaymentOperationExecutionRequestedV1.class
			).payload());
		} catch (InvalidIntegrationEventException poison) {
			return Optional.empty();
		}
	}

	private void redispatchQueuedGeneration(PaymentOperationExecutionRequestedV1 event) {
		Optional<PaymentOperation> found = operationRepository.findByOperationUidWithLock(
			event.operationUid());
		if (found.isEmpty()) {
			return;
		}
		PaymentOperation operation = found.orElseThrow();
		if (!operation.getReservation().getReservationUid().equals(event.reservationUid())) {
			return;
		}
		if (!operation.redispatchQuarantinedGeneration(event.dispatchGeneration(), clock.instant())) {
			return;
		}
		appendExecutionRequest(operation);
	}

	private void appendExecutionRequest(PaymentOperation operation) {
		outboxWriter.append(new PaymentOperationExecutionRequestedV1(
			operation.getOperationUid(),
			operation.getReservation().getReservationUid(),
			operation.getDispatchGeneration()
		));
	}
}
