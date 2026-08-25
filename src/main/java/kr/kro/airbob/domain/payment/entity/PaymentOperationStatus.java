package kr.kro.airbob.domain.payment.entity;

public enum PaymentOperationStatus {
	QUEUED,
	EXECUTING,
	WAITING_RETRY,
	APPLIED,
	DECLINED,
	MANUAL_REVIEW;

	public boolean isTerminal() {
		return this == APPLIED || this == DECLINED;
	}
}
