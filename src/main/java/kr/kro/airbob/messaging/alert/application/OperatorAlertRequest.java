package kr.kro.airbob.messaging.alert.application;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;

public record OperatorAlertRequest(
	OperatorAlertKind kind,
	UUID subjectUid,
	OperatorAlertSummaryCode summaryCode,
	OperatorAlertSourcePosition sourcePosition,
	UUID occurrenceUid
) {
	public OperatorAlertRequest {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(subjectUid, "subjectUid must not be null");
		Objects.requireNonNull(summaryCode, "summaryCode must not be null");
		Objects.requireNonNull(sourcePosition, "sourcePosition must not be null");
		Objects.requireNonNull(occurrenceUid, "occurrenceUid must not be null");
	}
}
