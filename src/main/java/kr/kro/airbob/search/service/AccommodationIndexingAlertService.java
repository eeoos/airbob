package kr.kro.airbob.search.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationIndexingAlertService {

	private static final String ALERT_MESSAGE = """
		[accommodation-indexing-quarantined]
		eventType=%s
		topic=%s
		partition=%d
		offset=%d
		accommodationUid=%s
		""";

	private final SlackNotificationService slackNotificationService;

	public void alertQuarantined(
		String topic,
		int partition,
		long offset,
		EventType eventType,
		UUID accommodationUid
	) {
		String alert = ALERT_MESSAGE.formatted(
			eventType != null ? eventType.name() : EventType.UNKNOWN.name(),
			topic,
			partition,
			offset,
			accommodationUid != null ? accommodationUid : "unavailable"
		);
		slackNotificationService.sendAlert(alert);
	}
}
