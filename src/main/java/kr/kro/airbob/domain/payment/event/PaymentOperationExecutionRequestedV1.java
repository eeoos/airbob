package kr.kro.airbob.domain.payment.event;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;

public record PaymentOperationExecutionRequestedV1(
	UUID operationUid,
	UUID reservationUid,
	long dispatchGeneration
) implements IntegrationEvent {
	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		"PAYMENT_OPERATION.events",
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
