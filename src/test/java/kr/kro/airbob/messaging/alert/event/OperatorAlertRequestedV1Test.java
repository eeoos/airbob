package kr.kro.airbob.messaging.alert.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@DisplayName("operator alert integration event contract")
class OperatorAlertRequestedV1Test {

	private static final UUID SUBJECT_UID =
		UUID.fromString("7b70cd2f-6393-430e-9b60-907fbeab6891");
	private static final UUID OCCURRENCE_UID =
		UUID.fromString("58389514-570c-43b6-a445-75ecb82d962b");

	@Test
	void containsOnlyAllowlistedIdentifiersAndUsesStableSubjectRouting() {
		OperatorAlertRequestedV1 event = OperatorAlertRequestedV1.create(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			new OperatorAlertSourcePosition("PAYMENT_OPERATION.events", 2, 41L),
			OCCURRENCE_UID
		);

		assertThat(event.descriptor()).isEqualTo(OperatorAlertRequestedV1.DESCRIPTOR);
		assertThat(event.aggregateId()).isEqualTo(event.alertUid().toString());
		assertThat(event.partitionKey()).isEqualTo(SUBJECT_UID.toString());
		assertThat(event.deduplicationKey())
			.isEqualTo("OPERATOR_ALERT:PAYMENT_OPERATION_QUARANTINED:"
				+ SUBJECT_UID + ":" + event.alertUid());

		IntegrationEventCodec codec = new IntegrationEventCodec(
			new ObjectMapper().findAndRegisterModules());
		String encoded = codec.encode(EventEnvelope.of(
			UUID.fromString("0e129431-0b47-46fd-af02-3874571cbb16"),
			java.time.Instant.parse("2026-08-17T00:00:00Z"),
			event
		));

		assertThat(encoded)
			.contains("\"alert_uid\"", "\"kind\"", "\"subject_uid\"",
				"\"source_topic\"", "\"source_partition\"", "\"source_offset\"",
				"\"summary_code\"")
			.doesNotContain("occurrence", "exception", "provider_key",
				"webhook_url", "payment_key", "user_text");
	}

	@Test
	void derivesTheSameAlertAndDedupeIdentityForTheSameOccurrence() {
		OperatorAlertRequestedV1 first = event(OCCURRENCE_UID);
		OperatorAlertRequestedV1 replay = event(OCCURRENCE_UID);
		OperatorAlertRequestedV1 nextOccurrence = event(UUID.randomUUID());

		assertThat(replay.alertUid()).isEqualTo(first.alertUid());
		assertThat(replay.deduplicationKey()).isEqualTo(first.deduplicationKey());
		assertThat(nextOccurrence.alertUid()).isNotEqualTo(first.alertUid());
	}

	@Test
	void rejectsPartialCoordinatesAndNonAllowlistedTopics() {
		assertThatThrownBy(() -> new OperatorAlertSourcePosition(
			"PAYMENT_OPERATION.events", null, 1L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorAlertSourcePosition(
			"attacker-controlled-topic", 0, 1L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private OperatorAlertRequestedV1 event(UUID occurrenceUid) {
		return OperatorAlertRequestedV1.create(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			OperatorAlertSourcePosition.none(),
			occurrenceUid
		);
	}
}
