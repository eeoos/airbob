package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.messaging.alert.application.OperatorAlertGateway;
import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;
import kr.kro.airbob.messaging.alert.monitoring.OperatorAlertMetrics;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;

@ExtendWith(MockitoExtension.class)
@DisplayName("operator alert Kafka listener")
class OperatorAlertKafkaListenerTest {

	@Mock private OperatorAlertGateway gateway;
	@Mock private OperatorAlertMetrics metrics;
	@Mock private Acknowledgment acknowledgment;
	private IntegrationEventCodec codec;
	private OperatorAlertKafkaListener listener;
	private OperatorAlertRequestedV1 event;
	private String message;

	@BeforeEach
	void setUp() {
		codec = new IntegrationEventCodec(new ObjectMapper().findAndRegisterModules());
		listener = new OperatorAlertKafkaListener(codec, gateway, metrics);
		event = OperatorAlertRequestedV1.create(
			OperatorAlertKind.ACCOMMODATION_INDEX_QUARANTINED,
			UUID.fromString("344e728f-4e7f-4a65-a923-5bddb38d359a"),
			OperatorAlertSummaryCode.INDEX_REFRESH_FAILED,
			new OperatorAlertSourcePosition(
				AccommodationSearchRefreshRequestedV1.TOPIC, 1, 8L),
			UUID.fromString("391a1782-a1ef-42dd-a1e0-9d940f45694b")
		);
		message = codec.encode(EventEnvelope.of(
			UUID.fromString("353482cb-aa4c-4c26-80ff-9fe1b9b35189"),
			java.time.Instant.parse("2026-08-17T00:00:00Z"), event));
	}

	@Test
	void acknowledgesOnlyAfterGatewayDeliveryAndMetricRecording() {
		listener.handle(message, acknowledgment);

		InOrder order = inOrder(gateway, metrics, acknowledgment);
		order.verify(gateway).deliver(event);
		order.verify(metrics).delivered();
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	void propagatesTransportFailureWithoutAcknowledging() {
		willThrow(new IllegalStateException("slack transport unavailable"))
			.given(gateway).deliver(event);

		assertThatThrownBy(() -> listener.handle(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class);
		then(metrics).should().failed();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	void rejectsForgedSourceTopicBeforeSlackDelivery() {
		String forged = message.replace(
			"\"source_topic\":\""
				+ AccommodationSearchRefreshRequestedV1.TOPIC + "\"",
			"\"source_topic\":\"EVIL.events\"");

		assertThatThrownBy(() -> listener.handle(forged, acknowledgment))
			.isInstanceOf(InvalidIntegrationEventException.class);

		then(gateway).shouldHaveNoInteractions();
		then(metrics).should().failed();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	void dltDoesNotCallTheGatewayAndAcknowledgesAfterSafeMetricHandling() {
		listener.handleDlt(
			message, OperatorAlertRequestedV1.TOPIC, 2, 99L, acknowledgment);

		InOrder order = inOrder(metrics, acknowledgment);
		order.verify(metrics).dlt();
		order.verify(acknowledgment).acknowledge();
		then(gateway).shouldHaveNoInteractions();
	}

	@Test
	void configuresDedicatedRetryAndDltWithoutTopicAutoCreation() throws Exception {
		var method = OperatorAlertKafkaListener.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retry = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafka = method.getAnnotation(KafkaListener.class);

		assertThat(retry.autoCreateTopics()).isEqualTo("false");
		assertThat(retry.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retry.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retry.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(retry.kafkaTemplate()).isEqualTo("operatorAlertRetryKafkaTemplate");
		assertThat(kafka.topics())
			.containsExactly(OperatorAlertRequestedV1.TOPIC);
		assertThat(kafka.autoStartup())
			.isEqualTo("${operator-alert.kafka.auto-startup:true}");
	}
}
