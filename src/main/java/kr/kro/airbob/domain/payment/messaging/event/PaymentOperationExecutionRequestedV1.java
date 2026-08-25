package kr.kro.airbob.domain.payment.messaging.event;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventDestination;

public record PaymentOperationExecutionRequestedV1(
	UUID operationUid,
	UUID reservationUid,
	long dispatchGeneration
) implements IntegrationEvent {
	public static final String TOPIC = IntegrationEventDestination.Topic.PAYMENT_OPERATION;
	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		IntegrationEventDestination.PAYMENT_OPERATION,
		"PAYMENT_OPERATION",
		"PAYMENT_OPERATION_EXECUTION_REQUESTED",
		"1"
	);

	public PaymentOperationExecutionRequestedV1 {
		Objects.requireNonNull(operationUid, "operationUid must not be null");
		Objects.requireNonNull(reservationUid, "reservationUid must not be null");
		if (dispatchGeneration <= 0) {
			throw new IllegalArgumentException("dispatchGeneration must be positive");
		}
	}

	@Override
	public EventDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public String aggregateId() {
		return operationUid.toString();
	}

	@Override
	public String partitionKey() {
		return reservationUid.toString();
	}

	@Override
	public String deduplicationKey() {
		return "PAYMENT_EXECUTION:" + operationUid + ":" + dispatchGeneration;
	}
}
