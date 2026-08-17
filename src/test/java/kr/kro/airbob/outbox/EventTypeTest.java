package kr.kro.airbob.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventTypeTest {

	@Test
	void paymentOperationExecutionUsesThePaymentOperationDebeziumTopic() {
		assertThat(EventType.PAYMENT_EXECUTION_REQUESTED_V1.getAggregateType()).isEqualTo("PAYMENT_OPERATION");
		assertThat(EventType.PAYMENT_EXECUTION_REQUESTED_V1.getTopic()).isEqualTo("PAYMENT_OPERATION.events");
	}
}
