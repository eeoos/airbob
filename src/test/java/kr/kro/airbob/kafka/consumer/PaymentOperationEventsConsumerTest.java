package kr.kro.airbob.kafka.consumer;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.SlackNotificationService;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;

@ExtendWith(MockitoExtension.class)
@DisplayName("payment-operation Kafka 소비자 테스트")
class PaymentOperationEventsConsumerTest {

	private static final UUID OPERATION_UID = UUID.fromString("b7f97942-3e28-4a5f-9cb4-797001b4f5c1");
	private static final UUID RESERVATION_UID = UUID.fromString("81eb3596-050b-42e9-845f-cc74d34b7cf2");
	private static final String MESSAGE = "payment-operation-envelope";

	@Mock private DebeziumEventParser parser;
	@Mock private PaymentOperationExecutor executor;
	@Mock private PaymentOperationAlertService alertService;
	@Mock private Acknowledgment acknowledgment;

	private PaymentOperationEventsConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new PaymentOperationEventsConsumer(parser, executor, alertService);
	}

	@Test
	@DisplayName("실행 결과가 내구 상태로 반영된 뒤에만 원본 메시지를 ACK한다")
	void executesThenAcknowledges() {
		PaymentExecutionRequestedV1 payload = new PaymentExecutionRequestedV1(OPERATION_UID, RESERVATION_UID);
		EventEnvelope<PaymentExecutionRequestedV1> envelope =
			EventEnvelope.of(PAYMENT_EXECUTION_REQUESTED_V1, payload, Instant.EPOCH);
		given(parser.getEventType(MESSAGE)).willReturn(PAYMENT_EXECUTION_REQUESTED_V1.name());
		given(parser.parse(MESSAGE, PaymentExecutionRequestedV1.class)).willReturn(envelope);

		consumer.handle(MESSAGE, acknowledgment);

		InOrder order = inOrder(executor, acknowledgment);
		order.verify(executor).execute(OPERATION_UID);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("파싱 실패는 재시도 경계로 전파하고 ACK하지 않는다")
	void rethrowsParsingFailureWithoutAck() {
		DebeziumEventParsingException failure =
			new DebeziumEventParsingException(new IllegalArgumentException("broken"));
		given(parser.getEventType(MESSAGE)).willThrow(failure);

		assertThatThrownBy(() -> consumer.handle(MESSAGE, acknowledgment))
			.isSameAs(failure);

		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실행기가 내구 상태를 만들지 못하면 실패를 전파하고 ACK하지 않는다")
	void rethrowsUndurableExecutionFailureWithoutAck() {
		PaymentExecutionRequestedV1 payload = new PaymentExecutionRequestedV1(OPERATION_UID, RESERVATION_UID);
		EventEnvelope<PaymentExecutionRequestedV1> envelope =
			EventEnvelope.of(PAYMENT_EXECUTION_REQUESTED_V1, payload, Instant.EPOCH);
		given(parser.getEventType(MESSAGE)).willReturn(PAYMENT_EXECUTION_REQUESTED_V1.name());
		given(parser.parse(MESSAGE, PaymentExecutionRequestedV1.class)).willReturn(envelope);
		willThrow(new IllegalStateException("database unavailable"))
			.given(executor).execute(OPERATION_UID);

		assertThatThrownBy(() -> consumer.handle(MESSAGE, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database unavailable");

		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("다른 이벤트 타입은 poison message로 거부하고 ACK하지 않는다")
	void rejectsUnsupportedEventWithoutAck() {
		given(parser.getEventType(MESSAGE)).willReturn("RESERVATION_PENDING");

		assertThatThrownBy(() -> consumer.handle(MESSAGE, acknowledgment))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("RESERVATION_PENDING");

		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT 알림에는 원본 좌표와 읽을 수 있는 operation UID만 전달하고 ACK한다")
	void quarantinesWithCoordinatesAndOperationUidThenAcknowledges() {
		PaymentExecutionRequestedV1 payload = new PaymentExecutionRequestedV1(OPERATION_UID, RESERVATION_UID);
		EventEnvelope<PaymentExecutionRequestedV1> envelope =
			EventEnvelope.of(PAYMENT_EXECUTION_REQUESTED_V1, payload, Instant.EPOCH);
		given(parser.getEventType(MESSAGE)).willReturn(PAYMENT_EXECUTION_REQUESTED_V1.name());
		given(parser.parse(MESSAGE, PaymentExecutionRequestedV1.class)).willReturn(envelope);

		consumer.handleDlt(
			MESSAGE,
			"PAYMENT_OPERATION.events",
			2,
			41L,
			"provider body paymentKey=secret virtualAccount=123",
			acknowledgment
		);

		then(alertService).should().alertQuarantined(
			"PAYMENT_OPERATION.events", 2, 41L, OPERATION_UID, "processing failure");
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("DLT payload가 깨져도 민감한 원문을 알리지 않고 operation UID 없이 ACK한다")
	void acknowledgesMalformedDltWithoutRawMessageAlert() {
		DebeziumEventParsingException failure =
			new DebeziumEventParsingException(new IllegalArgumentException("broken"));
		given(parser.getEventType(MESSAGE)).willThrow(failure);

		consumer.handleDlt(MESSAGE, "PAYMENT_OPERATION.events", 0, 7L, MESSAGE, acknowledgment);

		then(alertService).should().alertQuarantined(
			"PAYMENT_OPERATION.events", 0, 7L, null, "processing failure");
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("알림 전송 실패보다 MySQL 복구 상태를 우선하여 DLT를 ACK한다")
	void acknowledgesDltWhenAlertDeliveryFails() {
		willThrow(new IllegalStateException("slack unavailable"))
			.given(alertService)
			.alertQuarantined("PAYMENT_OPERATION.events", 1, 19L, null, "failure unavailable");

		consumer.handleDlt(
			MESSAGE, "PAYMENT_OPERATION.events", 1, 19L, null, acknowledgment);

		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("격리 알림 본문에는 허용된 복구 좌표만 포함한다")
	void alertBodyExcludesProviderAndMessageSecrets() {
		SlackNotificationService slackNotificationService = org.mockito.Mockito.mock(SlackNotificationService.class);
		PaymentOperationAlertService service = new PaymentOperationAlertService(slackNotificationService);
		String sensitiveFailure = "raw-message paymentKey=secret providerBody={card:4111} "
			+ "virtualAccount={accountNumber:123-456}";

		service.alertQuarantined(
			"PAYMENT_OPERATION.events", 3, 88L, OPERATION_UID, sensitiveFailure);

		ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
		then(slackNotificationService).should().sendAlert(alert.capture());
		assertThat(alert.getValue())
			.contains(
				"PAYMENT_EXECUTION_REQUESTED_V1",
				"PAYMENT_OPERATION.events",
				"partition=3",
				"offset=88",
				OPERATION_UID.toString())
			.doesNotContain(
				"raw-message",
				"paymentKey",
				"secret",
				"providerBody",
				"4111",
				"virtualAccount",
				"123-456");
	}

	@Test
	@DisplayName("전용 토픽, 그룹, 재시도 토픽, DLT 계약을 구성한다")
	void configuresDedicatedRetryAndDltContract() throws NoSuchMethodException {
		var method = PaymentOperationEventsConsumer.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts())
			.isEqualTo("${payment.operation.kafka.attempts:4}");
		assertThat(retryableTopic.backoff().delayExpression())
			.isEqualTo("${payment.operation.kafka.backoff-ms:30000}");
		assertThat(retryableTopic.kafkaTemplate()).isEqualTo("paymentOperationRetryKafkaTemplate");
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(kafkaListener).isNotNull();
		assertThat(kafkaListener.topics())
			.containsExactly("${payment.operation.kafka.topic:PAYMENT_OPERATION.events}");
		assertThat(kafkaListener.groupId())
			.isEqualTo("${payment.operation.kafka.group:payment-operation-execution-group}");
	}
}
