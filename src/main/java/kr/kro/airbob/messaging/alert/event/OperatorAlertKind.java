package kr.kro.airbob.messaging.alert.event;

/**
 * Operator alert categories are deliberately closed so external text can never become an alert
 * title or a low-cardinality metric tag by accident.
 */
public enum OperatorAlertKind {
	PAYMENT_MANUAL_REVIEW,
	PAYMENT_MANUAL_RESOLUTION,
	PAYMENT_OPERATION_QUARANTINED,
	ACCOMMODATION_INDEX_QUARANTINED,
	OUTBOX_BACKLOG_THRESHOLD_EXCEEDED
}
