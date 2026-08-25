package kr.kro.airbob.messaging.alert.event;

/** Safe, operator-facing summaries. Raw exception or provider messages must not cross this port. */
public enum OperatorAlertSummaryCode {
	PROVIDER_RESULT_UNKNOWN,
	RECONCILIATION_REQUESTED,
	RECONCILIATION_APPLIED,
	RECONCILIATION_DECLINED,
	RECONCILIATION_RETURNED_TO_REVIEW,
	PAYMENT_MARKED_NOT_PAID,
	MESSAGE_PROCESSING_FAILED,
	INDEX_REFRESH_FAILED,
	CACHE_INVALIDATION_FAILED,
	OUTBOX_DELIVERY_DELAYED
}
