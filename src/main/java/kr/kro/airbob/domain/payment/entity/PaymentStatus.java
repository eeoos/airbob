package kr.kro.airbob.domain.payment.entity;

public enum PaymentStatus {
	READY,
	IN_PROGRESS,
	WAITING_FOR_DEPOSIT,
	DONE,
	CANCELED,
	PARTIAL_CANCELED,
	ABORTED,
	EXPIRED;

	public static PaymentStatus from(String statusName) {
		if (statusName == null) {
			throw new IllegalArgumentException("결제 상태 값이 존재해야 합니다.");
		}
		return PaymentStatus.valueOf(statusName.toUpperCase());
	}
}
