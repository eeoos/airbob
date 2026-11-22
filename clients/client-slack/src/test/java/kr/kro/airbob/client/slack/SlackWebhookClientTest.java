package kr.kro.airbob.client.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Slack webhook client")
class SlackWebhookClientTest {

	private static final String WEBHOOK = "https://hooks.slack.test/services/secret-token";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final SlackWebhookClient client = new SlackWebhookClient(builder.build());

	@Test
	void postsTheTextAsJson() {
		server.expect(requestTo(WEBHOOK))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"text\":\"hello\"}", true))
			.andRespond(withSuccess());

		client.send(WEBHOOK, "hello");

		server.verify();
	}

	@Test
	void errorStatusFailsWithoutCauseOrWebhook() {
		server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

		assertConcealedFailure(catchThrowable(() -> client.send(WEBHOOK, "hello")));
	}

	@Test
	void redirectIsNotTreatedAsDelivered() {
		server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.FOUND));

		assertConcealedFailure(catchThrowable(() -> client.send(WEBHOOK, "hello")));
	}

	@Test
	void transportFailureFailsWithoutCauseOrWebhook() {
		server.expect(requestTo(WEBHOOK)).andRespond(request -> {
			throw new SocketTimeoutException("read timed out for " + WEBHOOK);
		});

		assertConcealedFailure(catchThrowable(() -> client.send(WEBHOOK, "hello")));
	}

	@Test
	void createUsesTheGivenConnectAndReadTimeouts() {
		SlackWebhookClient created = SlackWebhookClient.create(Duration.ofMillis(750), Duration.ofSeconds(3));

		Object restClient = ReflectionTestUtils.getField(created, "restClient");
		Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
		assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
		HttpClient httpClient = (HttpClient)ReflectionTestUtils.getField(requestFactory, "httpClient");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(750));
		assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(3));
	}

	private void assertConcealedFailure(Throwable failure) {
		assertThat(failure)
			.isInstanceOf(SlackWebhookException.class)
			.hasMessage("Slack webhook delivery failed.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain("secret-token");
	}
}
