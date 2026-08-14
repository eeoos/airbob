package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationAlertService {

	private static final String ALERT_MESSAGE = """
		[payment-operation-quarantined]
		eventType=%s
		topic=%s
		partition=%d
		offset=%d
		operationUid=%s
		""";

	private final SlackNotificationService slackNotificationService;

	public void alertQuarantined(
		String topic,
		int partition,
		long offset,
		UUID operationUid,
		String failureSummary
	) {
		// Exception messages are deliberately excluded because provider payloads and keys can be embedded in them.
		String alert = ALERT_MESSAGE.formatted(
			EventType.PAYMENT_EXECUTION_REQUESTED_V1.name(),
			topic,
			partition,
			offset,
			operationUid != null ? operationUid : "unavailable"
		);
		slackNotificationService.sendAlert(alert);
	}
}
