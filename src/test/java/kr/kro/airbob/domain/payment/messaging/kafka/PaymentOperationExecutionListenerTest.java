package kr.kro.airbob.domain.payment.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.service.PaymentOperationDltIncidentService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;

@ExtendWith(MockitoExtension.class)
@DisplayName("payment-operation Kafka 소비자 테스트")
class PaymentOperationExecutionListenerTest {

	private static final UUID OPERATION_UID = UUID.fromString("b7f97942-3e28-4a5f-9cb4-797001b4f5c1");
	private static final String RAW_SECRET = "poison-provider-secret-7a8e0d";
	private static final String MESSAGE = """
		{
		  "event_id": "7c245552-9212-4531-ac24-3fe0c64376f3",
		  "event_type": "PAYMENT_OPERATION_EXECUTION_REQUESTED",
		  "event_version": "1",
		  "occurred_at": "2026-08-14T00:00:00Z",
		  "payload": {
		    "operation_uid": "b7f97942-3e28-4a5f-9cb4-797001b4f5c1",
		    "reservation_uid": "81eb3596-050b-42e9-845f-cc74d34b7cf2",
		    "dispatch_generation": 3
		  }
		}
		""";

	@Mock private PaymentOperationExecutor executor;
	@Mock private PaymentOperationDltIncidentService dltIncidentService;
	@Mock private Acknowledgment acknowledgment;

	private PaymentOperationExecutionListener listener;

	@BeforeEach
	void setUp() {
		listener = new PaymentOperationExecutionListener(
			new IntegrationEventCodec(new ObjectMapper().findAndRegisterModules()),
			executor,
			dltIncidentService);
	}

	@Test
	@DisplayName("실행 결과가 내구 상태로 반영된 뒤에만 원본 메시지를 ACK한다")
	void executesThenAcknowledges() {
		listener.handle(MESSAGE, acknowledgment);

		InOrder order = inOrder(executor, acknowledgment);
		order.verify(executor).execute(OPERATION_UID, 3);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("파싱 실패는 원문을 보존하지 않는 예외로 전파하고 ACK하지 않는다")
	void rethrowsPayloadFreeParsingFailureWithoutAck() {
		String malformed = "not-json paymentKey=" + RAW_SECRET;

		Throwable failure = catchThrowable(() -> listener.handle(malformed, acknowledgment));

		assertThat(failure)
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, malformed);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실행기가 내구 상태를 만들지 못하면 실패를 전파하고 ACK하지 않는다")
	void rethrowsUndurableExecutionFailureWithoutAck() {
		willThrow(new IllegalStateException("database unavailable"))
			.given(executor).execute(OPERATION_UID, 3);

		assertThatThrownBy(() -> listener.handle(MESSAGE, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database unavailable");

		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("다른 이벤트 타입도 공격자 제어 문자열을 보존하지 않는 poison 예외로 거부한다")
	void rejectsUnsupportedEventWithoutRetainingTypeOrPayload() {
		String unsupported = """
			{"event_type":"%s","payload":{"operation_uid":"%s"}}
			""".formatted(RAW_SECRET, OPERATION_UID);

		Throwable failure = catchThrowable(() -> listener.handle(unsupported, acknowledgment));

		assertThat(failure)
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, unsupported);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("중복 operation_uid로 숨긴 원문은 실행하지 않고 poison으로 거부한다")
	void rejectsDuplicateTreeKeys() {
		String duplicated = MESSAGE.replace(
			"\"operation_uid\": \"" + OPERATION_UID + "\"",
			"\"operation_uid\": \"" + RAW_SECRET + "\", "
				+ "\"operation_uid\": \"" + OPERATION_UID + "\"");

		Throwable failure = catchThrowable(() -> listener.handle(duplicated, acknowledgment));

		assertThat(failure)
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, duplicated);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("정상 envelope 뒤에 붙인 두 번째 JSON은 실행하지 않고 poison으로 거부한다")
	void rejectsTrailingJsonValue() {
		String trailing = MESSAGE + "{\"provider_secret\":\"" + RAW_SECRET + "\"}";

		Throwable failure = catchThrowable(() -> listener.handle(trailing, acknowledgment));

		assertThat(failure)
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, trailing);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT incident 트랜잭션이 commit된 뒤에만 ACK한다")
	void recordsCanonicalIncidentThenAcknowledges() {
		ConsumerRecord<String, String> record = dltRecord(MESSAGE, 2, 41L);

		listener.handleDlt(record, acknowledgment);

		OperatorAlertSourcePosition source = OperatorAlertSourcePosition.from(
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.TOPIC,
			2,
			41L);
		InOrder order = inOrder(dltIncidentService, acknowledgment);
		order.verify(dltIncidentService).record(MESSAGE, source);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("poison DLT도 좌표와 raw value를 incident service에 맡기고 commit 뒤 ACK한다")
	void recordsMalformedDltWithoutDecodingInListener() {
		String poison = "not-json paymentKey=" + RAW_SECRET;
		ConsumerRecord<String, String> record = dltRecord(poison, 0, 7L);

		listener.handleDlt(record, acknowledgment);

		then(dltIncidentService).should().record(
			poison,
			OperatorAlertSourcePosition.from(
				PaymentOperationExecutionRequestedV1.DESCRIPTOR,
				PaymentOperationExecutionRequestedV1.TOPIC,
				0,
				7L));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("공격자가 조작한 original topic은 알림 source에 사용하지 않는다")
	void rejectsAttackerControlledOriginalTopicAtTheAlertBoundary() {
		ConsumerRecord<String, String> record = dltRecord(
			MESSAGE, "EVIL.events", 8, 777L);

		assertThatThrownBy(() -> listener.handleDlt(record, acknowledgment))
			.isInstanceOf(IllegalArgumentException.class);

		then(dltIncidentService).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT incident DB/outbox 실패는 전파하고 ACK하지 않는다")
	void incidentFailurePropagatesWithoutAck() {
		ConsumerRecord<String, String> record = dltRecord("not-json", 1, 19L);
		OperatorAlertSourcePosition source = OperatorAlertSourcePosition.from(
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.TOPIC,
			1,
			19L);
		willThrow(new IllegalStateException("operator alert outbox unavailable"))
			.given(dltIncidentService).record("not-json", source);

		assertThatThrownBy(() -> listener.handleDlt(record, acknowledgment))
			.isInstanceOf(IllegalStateException.class);

		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("전용 토픽, 그룹, 재시도 토픽, DLT 계약을 구성한다")
	void configuresDedicatedRetryAndDltContract() throws NoSuchMethodException {
		var method = PaymentOperationExecutionListener.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts())
			.isEqualTo("${payment.operation.kafka.attempts:4}");
		assertThat(retryableTopic.backoff().delayExpression())
			.isEqualTo("${payment.operation.kafka.backoff-ms:30000}");
		assertThat(retryableTopic.kafkaTemplate()).isEqualTo("paymentOperationRetryKafkaTemplate");
		assertThat(retryableTopic.listenerContainerFactory())
			.isEqualTo("paymentOperationKafkaListenerContainerFactory");
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(retryableTopic.autoCreateTopics()).isEqualTo("false");
		assertThat(kafkaListener).isNotNull();
		assertThat(kafkaListener.containerFactory())
			.isEqualTo("paymentOperationKafkaListenerContainerFactory");
		assertThat(kafkaListener.topics())
			.containsExactly(PaymentOperationExecutionRequestedV1.TOPIC);
		assertThat(kafkaListener.groupId())
			.isEqualTo("${payment.operation.kafka.group:payment-operation-execution-group}");
	}

	private ConsumerRecord<String, String> dltRecord(String value, int partition, long offset) {
		return dltRecord(
			value, PaymentOperationExecutionRequestedV1.TOPIC, partition, offset);
	}

	private ConsumerRecord<String, String> dltRecord(
		String value,
		String originalTopic,
		int partition,
		long offset
	) {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			PaymentOperationExecutionRequestedV1.TOPIC + ".DLT", 0, 99L, null, value);
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			originalTopic.getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
		return record;
	}
}
