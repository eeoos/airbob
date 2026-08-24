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

	public static OperatorAlertRequest paymentReconciliationRequested(
		UUID operationUid, long dispatchGeneration
	) {
		return paymentManualResolution(
			operationUid, dispatchGeneration, OperatorAlertSummaryCode.RECONCILIATION_REQUESTED);
	}

	public static OperatorAlertRequest paymentReconciliationApplied(
		UUID operationUid, long dispatchGeneration
	) {
		return paymentManualResolution(
			operationUid, dispatchGeneration, OperatorAlertSummaryCode.RECONCILIATION_APPLIED);
	}

	public static OperatorAlertRequest paymentReconciliationDeclined(
		UUID operationUid, long dispatchGeneration
	) {
		return paymentManualResolution(
			operationUid, dispatchGeneration, OperatorAlertSummaryCode.RECONCILIATION_DECLINED);
	}

	public static OperatorAlertRequest paymentReconciliationReturnedToReview(
		UUID operationUid, long dispatchGeneration
	) {
		return paymentManualResolution(
			operationUid,
			dispatchGeneration,
			OperatorAlertSummaryCode.RECONCILIATION_RETURNED_TO_REVIEW);
	}

	public static OperatorAlertRequest paymentMarkedNotPaid(
		UUID operationUid, long dispatchGeneration
	) {
		return paymentManualResolution(
			operationUid, dispatchGeneration, OperatorAlertSummaryCode.PAYMENT_MARKED_NOT_PAID);
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

	public static OperatorAlertRequest accommodationCacheQuarantined(
		OperatorAlertSourcePosition sourcePosition
	) {
		return quarantined(
			OperatorAlertKind.ACCOMMODATION_CACHE_QUARANTINED,
			null,
			OperatorAlertSummaryCode.CACHE_INVALIDATION_FAILED,
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

	private static OperatorAlertRequest paymentManualResolution(
		UUID operationUid,
		long dispatchGeneration,
		OperatorAlertSummaryCode summaryCode
	) {
		Objects.requireNonNull(operationUid, "operationUid must not be null");
		if (dispatchGeneration <= 0) {
			throw new IllegalArgumentException("dispatchGeneration must be positive");
		}
		return new OperatorAlertRequest(
			OperatorAlertKind.PAYMENT_MANUAL_RESOLUTION,
			operationUid,
			summaryCode,
			OperatorAlertSourcePosition.none(),
			deterministicUid(
				"PAYMENT_MANUAL_RESOLUTION:" + operationUid + ":"
					+ dispatchGeneration + ":" + summaryCode)
		);
	}

	private static UUID deterministicUid(String identity) {
		return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
	}
}
