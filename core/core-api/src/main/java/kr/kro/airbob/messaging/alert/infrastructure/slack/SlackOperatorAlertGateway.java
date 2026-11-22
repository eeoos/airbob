package kr.kro.airbob.messaging.alert.infrastructure.slack;

import org.springframework.stereotype.Component;

import kr.kro.airbob.client.slack.SlackWebhookClient;
import kr.kro.airbob.messaging.alert.application.OperatorAlertGateway;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

@Component
public class SlackOperatorAlertGateway implements OperatorAlertGateway {

	private final SlackWebhookClient slackWebhookClient;
	private final OperatorAlertSlackProperties properties;

	public SlackOperatorAlertGateway(
		SlackWebhookClient slackWebhookClient,
		OperatorAlertSlackProperties properties
	) {
		this.slackWebhookClient = slackWebhookClient;
		this.properties = properties;
	}

	@Override
	public void deliver(OperatorAlertRequestedV1 alert) {
		if (!properties.deliveryConfigured()) {
			throw new OperatorAlertDeliveryNotConfiguredException();
		}

		try {
			slackWebhookClient.send(properties.webhookUrl(), message(alert));
		} catch (RuntimeException deliveryFailure) {
			throw new OperatorAlertDeliveryException();
		}
	}

	private String message(OperatorAlertRequestedV1 alert) {
		StringBuilder message = new StringBuilder(256)
			.append("[Airbob operator alert]")
			.append("\nkind=").append(alert.kind())
			.append("\nsummary_code=").append(alert.summaryCode())
			.append("\nalert_uid=").append(alert.alertUid())
			.append("\nsubject_uid=").append(alert.subjectUid());
		if (alert.sourcePosition().present()) {
			message.append("\nsource_topic=").append(alert.sourceTopic())
				.append("\nsource_partition=").append(alert.sourcePartition())
				.append("\nsource_offset=").append(alert.sourceOffset());
		}
		return message.toString();
	}
}
