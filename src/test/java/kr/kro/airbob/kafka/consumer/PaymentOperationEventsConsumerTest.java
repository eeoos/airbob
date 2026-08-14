package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.outbox.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("payment-operation Kafka 소비자 테스트")
class PaymentOperationEventsConsumerTest {

	private static final UUID OPERATION_UID = UUID.fromString("b7f97942-3e28-4a5f-9cb4-797001b4f5c1");
	private static final String RAW_SECRET = "poison-provider-secret-7a8e0d";
	private static final String MESSAGE = """
		{
		  "event_type": "PAYMENT_EXECUTION_REQUESTED_V1",
		  "payload": {
		    "operation_uid": "b7f97942-3e28-4a5f-9cb4-797001b4f5c1",
		    "reservation_uid": "81eb3596-050b-42e9-845f-cc74d34b7cf2"
		  }
		}
		""";

	@Mock private PaymentOperationExecutor executor;
	@Mock private PaymentOperationAlertService alertService;
	@Mock private Acknowledgment acknowledgment;

	private PaymentOperationEventsConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new PaymentOperationEventsConsumer(
			new PaymentOperationEventParser(new ObjectMapper()), executor, alertService);
	}

	@Test
	@DisplayName("실행 결과가 내구 상태로 반영된 뒤에만 원본 메시지를 ACK한다")
	void executesThenAcknowledges() {
		consumer.handle(MESSAGE, acknowledgment);

		InOrder order = inOrder(executor, acknowledgment);
		order.verify(executor).execute(OPERATION_UID);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("파싱 실패는 원문을 보존하지 않는 예외로 전파하고 ACK하지 않는다")
	void rethrowsPayloadFreeParsingFailureWithoutAck() {
		String malformed = "not-json paymentKey=" + RAW_SECRET;

		Throwable failure = catchThrowable(() -> consumer.handle(malformed, acknowledgment));

		assertThat(failure)
			.isInstanceOf(PaymentOperationEventParsingException.class)
			.hasMessage("Invalid payment-operation event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, malformed);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실행기가 내구 상태를 만들지 못하면 실패를 전파하고 ACK하지 않는다")
	void rethrowsUndurableExecutionFailureWithoutAck() {
		willThrow(new IllegalStateException("database unavailable"))
			.given(executor).execute(OPERATION_UID);

		assertThatThrownBy(() -> consumer.handle(MESSAGE, acknowledgment))
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

		Throwable failure = catchThrowable(() -> consumer.handle(unsupported, acknowledgment));

		assertThat(failure)
			.isInstanceOf(PaymentOperationEventParsingException.class)
			.hasMessage("Invalid payment-operation event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(RAW_SECRET, unsupported);
		then(executor).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT 알림에는 원본 좌표와 읽을 수 있는 operation UID만 전달하고 ACK한다")
	void quarantinesWithCoordinatesAndOperationUidThenAcknowledges() {
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
		consumer.handleDlt(
			"not-json paymentKey=" + RAW_SECRET,
			"PAYMENT_OPERATION.events",
			0,
			7L,
			RAW_SECRET,
			acknowledgment
		);

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
			"not-json", "PAYMENT_OPERATION.events", 1, 19L, null, acknowledgment);

		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("격리 알림 본문에는 허용된 복구 좌표만 포함한다")
	void alertBodyExcludesProviderAndMessageSecrets() {
		SlackNotificationService slackNotificationService =
			org.mockito.Mockito.mock(SlackNotificationService.class);
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
