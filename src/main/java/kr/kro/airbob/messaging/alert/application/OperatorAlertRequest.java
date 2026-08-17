package kr.kro.airbob.messaging.alert.application;

import java.nio.charset.StandardCharsets;
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

	public static OperatorAlertRequest paymentManualReview(UUID operationUid, int manualReviewCount) {
		Objects.requireNonNull(operationUid, "operationUid must not be null");
		if (manualReviewCount <= 0) {
			throw new IllegalArgumentException("manualReviewCount must be positive");
		}
		return new OperatorAlertRequest(
			OperatorAlertKind.PAYMENT_MANUAL_REVIEW,
			operationUid,
			OperatorAlertSummaryCode.PROVIDER_RESULT_UNKNOWN,
			OperatorAlertSourcePosition.none(),
			deterministicUid("PAYMENT_MANUAL_REVIEW:" + operationUid + ":" + manualReviewCount)
		);
	}

	public static OperatorAlertRequest paymentOperationQuarantined(
		UUID operationUid,
		OperatorAlertSourcePosition sourcePosition
	) {
		return quarantined(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			operationUid,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			sourcePosition
		);
	}

	public static OperatorAlertRequest accommodationIndexQuarantined(
		UUID accommodationUid,
		OperatorAlertSourcePosition sourcePosition
	) {
		return quarantined(
			OperatorAlertKind.ACCOMMODATION_INDEX_QUARANTINED,
			accommodationUid,
			OperatorAlertSummaryCode.INDEX_REFRESH_FAILED,
			sourcePosition
		);
	}

	private static OperatorAlertRequest quarantined(
		OperatorAlertKind kind,
		UUID decodedSubjectUid,
		OperatorAlertSummaryCode summaryCode,
		OperatorAlertSourcePosition sourcePosition
	) {
		Objects.requireNonNull(sourcePosition, "sourcePosition must not be null");
		if (!sourcePosition.present()) {
			throw new IllegalArgumentException("quarantine source position must be present");
		}
		UUID coordinateUid = deterministicUid(
			kind + ":" + sourcePosition.topic() + ":"
				+ sourcePosition.partition() + ":" + sourcePosition.offset());
		return new OperatorAlertRequest(
			kind,
			decodedSubjectUid != null ? decodedSubjectUid : coordinateUid,
			summaryCode,
			sourcePosition,
			coordinateUid
		);
	}

	private static UUID deterministicUid(String identity) {
		return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
	}
}
