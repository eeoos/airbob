package kr.kro.airbob.messaging.alert.event;

import java.util.Objects;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEventDestination;

public record OperatorAlertSourcePosition(
	String topic,
	Integer partition,
	Long offset
) {

	public OperatorAlertSourcePosition {
		boolean empty = topic == null && partition == null && offset == null;
		boolean complete = topic != null && partition != null && offset != null;
		if (!empty && !complete) {
			throw new IllegalArgumentException("source position must be complete or empty");
		}
		if (complete && (!IntegrationEventDestination.isOperatorAlertDltSource(topic)
			|| partition < 0
			|| offset < 0)) {
			throw new IllegalArgumentException("source position is invalid");
		}
	}

	public static OperatorAlertSourcePosition from(
		EventDescriptor trustedSource,
		String observedOriginalTopic,
		int partition,
		long offset
	) {
		Objects.requireNonNull(trustedSource, "trustedSource must not be null");
		if (!trustedSource.destination().equals(observedOriginalTopic)) {
			throw new IllegalArgumentException(
				"observed source topic does not match trusted descriptor");
		}
		return new OperatorAlertSourcePosition(
			trustedSource.destination(), partition, offset);
	}

	public static OperatorAlertSourcePosition none() {
		return new OperatorAlertSourcePosition(null, null, null);
	}

	public boolean present() {
		return topic != null;
	}
}
