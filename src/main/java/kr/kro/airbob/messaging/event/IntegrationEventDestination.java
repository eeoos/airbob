package kr.kro.airbob.messaging.event;

public enum IntegrationEventDestination {

	PAYMENT_OPERATION(Topic.PAYMENT_OPERATION, true),
	ACCOMMODATION_INDEX(Topic.ACCOMMODATION_INDEX, true),
	ACCOMMODATION_CACHE(Topic.ACCOMMODATION_CACHE, true),
	OPERATOR_ALERT(Topic.OPERATOR_ALERT, false);

	private final String topic;
	private final boolean operatorAlertDltSource;

	IntegrationEventDestination(String topic, boolean operatorAlertDltSource) {
		this.topic = topic;
		this.operatorAlertDltSource = operatorAlertDltSource;
	}

	public String topic() {
		return topic;
	}

	public static boolean isOperatorAlertDltSource(String topic) {
		for (IntegrationEventDestination destination : values()) {
			if (destination.topic.equals(topic)) {
				return destination.operatorAlertDltSource;
			}
		}
		return false;
	}

	public static final class Topic {

		public static final String PAYMENT_OPERATION = "PAYMENT_OPERATION.events";
		public static final String ACCOMMODATION_INDEX = "ACCOMMODATION_INDEX.events";
		public static final String ACCOMMODATION_CACHE = "ACCOMMODATION_CACHE.events";
		public static final String OPERATOR_ALERT = "OPERATOR_ALERT.events";

		private Topic() {
		}
	}
}
