package kr.kro.airbob.messaging.alert.infrastructure.slack;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.messaging.alert.application.OperatorAlertGateway;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

@Component
public class SlackOperatorAlertGateway implements OperatorAlertGateway {

	private final RestClient restClient;
	private final OperatorAlertSlackProperties properties;

	public SlackOperatorAlertGateway(
		@Qualifier("operatorAlertRestClient") RestClient restClient,
		OperatorAlertSlackProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public void deliver(OperatorAlertRequestedV1 alert) {
		if (!properties.deliveryConfigured()) {
			throw new OperatorAlertDeliveryNotConfiguredException();
		}

		try {
			restClient.post()
				.uri(properties.webhookUrl())
				.body(Map.of("text", message(alert)))
				.retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
					throw new OperatorAlertDeliveryException();
				})
				.toBodilessEntity();
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
