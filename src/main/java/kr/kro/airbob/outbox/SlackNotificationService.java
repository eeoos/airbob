package kr.kro.airbob.outbox;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import io.micrometer.common.util.StringUtils;
import kr.kro.airbob.geo.dto.IpInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SlackNotificationService {

	@Value("${slack.webhook.url}")
	private String slackWebhookUrl;

	@Value("${slack.notification.enabled}")
	private boolean notificationEnabled;

	private final RestClient restClient;

	public SlackNotificationService(@Qualifier("generalRestClient") RestClient restClient) {
		this.restClient = restClient;
	}

	public void sendAlert(String message) {
		if (!notificationEnabled || StringUtils.isBlank(slackWebhookUrl)) {
			log.warn("Slack 알림이 비활성화되어 있거나 웹훅 URL이 설정되지 않음");
			return;
		}

		try {
			Map<String, String> payload = Map.of("text", message);

			restClient.post()
				.uri(slackWebhookUrl)
				.body(payload)
				.retrieve()
				.toBodilessEntity();

		} catch (Exception e) {
			log.error("Slack 알림 발송 실패: {}", e.getMessage(), e);
		}
	}
}
