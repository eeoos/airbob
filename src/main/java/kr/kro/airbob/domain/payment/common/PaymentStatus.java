package kr.kro.airbob.domain.payment.common;

public enum PaymentStatus {
	READY,
	IN_PROGRESS,
	WAITING_FOR_DEPOSIT,
	DONE,
	CANCELED,
	PARTIAL_CANCELED,
	ABORTED,
	EXPIRED
}
