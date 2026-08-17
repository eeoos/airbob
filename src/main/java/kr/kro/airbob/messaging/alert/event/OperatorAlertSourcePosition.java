package kr.kro.airbob.messaging.alert.event;

import java.util.Set;

public record OperatorAlertSourcePosition(
	String topic,
	Integer partition,
	Long offset
) {

	private static final Set<String> ALLOWED_TOPICS = Set.of(
		"PAYMENT_OPERATION.events",
		"ACCOMMODATION_INDEX.events"
	);

	public OperatorAlertSourcePosition {
		boolean empty = topic == null && partition == null && offset == null;
		boolean complete = topic != null && partition != null && offset != null;
		if (!empty && !complete) {
			throw new IllegalArgumentException("source position must be complete or empty");
		}
		if (complete && (!ALLOWED_TOPICS.contains(topic) || partition < 0 || offset < 0)) {
			throw new IllegalArgumentException("source position is not allowlisted");
		}
	}

	public static OperatorAlertSourcePosition none() {
		return new OperatorAlertSourcePosition(null, null, null);
	}

	public boolean present() {
		return topic != null;
	}
}
