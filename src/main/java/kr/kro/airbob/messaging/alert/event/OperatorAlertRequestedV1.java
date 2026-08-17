package kr.kro.airbob.messaging.alert.event;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;

public record OperatorAlertRequestedV1(
	UUID alertUid,
	OperatorAlertKind kind,
	UUID subjectUid,
	String sourceTopic,
	Integer sourcePartition,
	Long sourceOffset,
	OperatorAlertSummaryCode summaryCode
) implements IntegrationEvent {
	public static final String TOPIC = "OPERATOR_ALERT.events";

	public static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		TOPIC,
		"OPERATOR_ALERT",
		"OPERATOR_ALERT_REQUESTED",
		"1"
	);

	public OperatorAlertRequestedV1 {
		Objects.requireNonNull(alertUid, "alertUid must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(subjectUid, "subjectUid must not be null");
		Objects.requireNonNull(summaryCode, "summaryCode must not be null");
		new OperatorAlertSourcePosition(sourceTopic, sourcePartition, sourceOffset);
	}

	public static OperatorAlertRequestedV1 create(
		OperatorAlertKind kind,
		UUID subjectUid,
		OperatorAlertSummaryCode summaryCode,
		OperatorAlertSourcePosition source,
		UUID occurrenceUid
	) {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(subjectUid, "subjectUid must not be null");
		Objects.requireNonNull(summaryCode, "summaryCode must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(occurrenceUid, "occurrenceUid must not be null");
		UUID alertUid = UUID.nameUUIDFromBytes(
			(kind.name() + ":" + subjectUid + ":" + occurrenceUid)
				.getBytes(StandardCharsets.UTF_8));
		return new OperatorAlertRequestedV1(
			alertUid,
			kind,
			subjectUid,
			source.topic(),
			source.partition(),
			source.offset(),
			summaryCode
		);
	}

	@Override
	public EventDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public String aggregateId() {
		return alertUid.toString();
	}

	@Override
	public String partitionKey() {
		return subjectUid.toString();
	}

	@Override
	public String deduplicationKey() {
		return "OPERATOR_ALERT:" + kind + ":" + subjectUid + ":" + alertUid;
	}

	public OperatorAlertSourcePosition sourcePosition() {
		return new OperatorAlertSourcePosition(sourceTopic, sourcePartition, sourceOffset);
	}
}
