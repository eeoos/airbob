package kr.kro.airbob.messaging.alert.infrastructure.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;

@DisplayName("Slack operator alert gateway")
class SlackOperatorAlertGatewayTest {

	private static final String WEBHOOK = "https://hooks.slack.test/services/safe";
	private static final String SECRET = "provider-payment-key-secret";

	@Test
	void postsAnAllowlistedBodyOnly() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		SlackOperatorAlertGateway gateway = new SlackOperatorAlertGateway(
			builder.build(), enabledProperties());
		server.expect(requestTo(WEBHOOK))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().string(org.hamcrest.Matchers.allOf(
				org.hamcrest.Matchers.containsString("PAYMENT_OPERATION_QUARANTINED"),
				org.hamcrest.Matchers.containsString("MESSAGE_PROCESSING_FAILED"),
				org.hamcrest.Matchers.containsString("PAYMENT_OPERATION.events"),
				org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))
			)))
			.andRespond(withSuccess());

		gateway.deliver(event());
		server.verify();
	}

	@Test
	void propagatesNon2xxSoKafkaCanRetry() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		SlackOperatorAlertGateway gateway = new SlackOperatorAlertGateway(
			builder.build(), enabledProperties());
		server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

		Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
			() -> gateway.deliver(event()));
		assertThat(failure)
			.isInstanceOf(OperatorAlertDeliveryException.class)
			.hasMessage("Operator alert delivery failed.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(WEBHOOK);
	}

	@Test
	void rejectsRedirectsSoKafkaDoesNotAcknowledgeAnUndeliveredAlert() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		SlackOperatorAlertGateway gateway = new SlackOperatorAlertGateway(
			builder.build(), enabledProperties());
		server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.FOUND));

		assertThatThrownBy(() -> gateway.deliver(event()))
			.isInstanceOf(OperatorAlertDeliveryException.class)
			.hasMessage("Operator alert delivery failed.")
			.hasNoCause();
	}

	@Test
	void propagatesTransportFailureSoKafkaCanRetry() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		SlackOperatorAlertGateway gateway = new SlackOperatorAlertGateway(
			builder.build(), enabledProperties());
		server.expect(requestTo(WEBHOOK)).andRespond(request -> {
			throw new java.net.SocketTimeoutException("read timed out");
		});

		Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
			() -> gateway.deliver(event()));
		assertThat(failure)
			.isInstanceOf(OperatorAlertDeliveryException.class)
			.hasMessage("Operator alert delivery failed.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(WEBHOOK);
	}

	@Test
	void disabledOrMissingWebhookFailsWithoutMakingARequest() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		assertThatThrownBy(() -> new SlackOperatorAlertGateway(
			builder.build(), properties(false, WEBHOOK)).deliver(event()))
			.isInstanceOf(OperatorAlertDeliveryNotConfiguredException.class)
			.hasNoCause();
		assertThatThrownBy(() -> new SlackOperatorAlertGateway(
			builder.build(), properties(true, "")).deliver(event()))
			.isInstanceOf(OperatorAlertDeliveryNotConfiguredException.class)
			.hasNoCause();
		server.verify();
	}

	@Test
	void configurationUsesExplicitConnectAndReadTimeouts() {
		OperatorAlertSlackConfiguration configuration =
			new OperatorAlertSlackConfiguration(enabledProperties());

		RestClient client = configuration.operatorAlertRestClient();
		Object requestFactory = org.springframework.test.util.ReflectionTestUtils
			.getField(client, "clientRequestFactory");
		assertThat(requestFactory)
			.isInstanceOf(org.springframework.http.client.JdkClientHttpRequestFactory.class);
		java.net.http.HttpClient httpClient = (java.net.http.HttpClient)
			org.springframework.test.util.ReflectionTestUtils.getField(requestFactory, "httpClient");
		Duration readTimeout = (Duration)org.springframework.test.util.ReflectionTestUtils
			.getField(requestFactory, "readTimeout");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(750));
		assertThat(readTimeout).isEqualTo(Duration.ofSeconds(3));
	}

	private OperatorAlertRequestedV1 event() {
		return OperatorAlertRequestedV1.create(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			UUID.fromString("768bb2d9-318c-42eb-a126-f930c2da3c44"),
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			new OperatorAlertSourcePosition("PAYMENT_OPERATION.events", 0, 19L),
			UUID.fromString("81b49546-cee2-4995-bf4a-ff2cf154fdcf")
		);
	}

	private OperatorAlertSlackProperties enabledProperties() {
		return properties(true, WEBHOOK);
	}

	private OperatorAlertSlackProperties properties(boolean enabled, String webhook) {
		return new OperatorAlertSlackProperties(
			enabled, webhook, Duration.ofMillis(750), Duration.ofSeconds(3));
	}
}
