package kr.kro.airbob.client.slack;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Posts plain-text messages to a Slack incoming webhook.
 * Only a 2xx response counts as delivered; redirects, error statuses, and transport failures raise
 * {@link SlackWebhookException}. The webhook URL is a secret, so failures never expose it.
 */
public class SlackWebhookClient {

	private final RestClient restClient;

	public SlackWebhookClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public static SlackWebhookClient create(Duration connectTimeout, Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		return new SlackWebhookClient(RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build());
	}

	public void send(String webhookUrl, String text) {
		try {
			restClient.post()
				.uri(webhookUrl)
				.body(Map.of("text", text))
				.retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
					throw new SlackWebhookException();
				})
				.toBodilessEntity();
		} catch (RuntimeException failure) {
			throw new SlackWebhookException();
		}
	}
}
