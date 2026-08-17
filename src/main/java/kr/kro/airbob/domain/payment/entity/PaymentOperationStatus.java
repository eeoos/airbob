package kr.kro.airbob.domain.payment.entity;

public enum PaymentOperationStatus {
	READY, EXECUTING, RETRY_WAIT, OUTCOME_UNKNOWN, APPLIED, DECLINED, MANUAL_REVIEW;

	public boolean isTerminal() {
		return this == APPLIED || this == DECLINED || this == MANUAL_REVIEW;
	}
}
