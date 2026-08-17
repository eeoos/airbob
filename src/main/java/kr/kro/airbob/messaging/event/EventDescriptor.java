package kr.kro.airbob.messaging.event;

public record EventDescriptor(
	String destination,
	String aggregateType,
	String eventType,
	String eventVersion
) {
	public EventDescriptor {
		requireText(destination, "destination");
		requireText(aggregateType, "aggregateType");
		requireText(eventType, "eventType");
		requireText(eventVersion, "eventVersion");
	}

	private static void requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
	}
}
