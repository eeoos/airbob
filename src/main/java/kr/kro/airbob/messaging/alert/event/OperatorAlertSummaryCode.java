package kr.kro.airbob.messaging.alert.event;

/** Safe, operator-facing summaries. Raw exception or provider messages must not cross this port. */
public enum OperatorAlertSummaryCode {
	PROVIDER_RESULT_UNKNOWN,
	MESSAGE_PROCESSING_FAILED,
	INDEX_REFRESH_FAILED,
	OUTBOX_DELIVERY_DELAYED
}
