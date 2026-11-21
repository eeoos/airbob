package kr.kro.airbob.domain.payment.service;

public enum PaymentExecutionMode {
	CONFIRM,
	INQUIRE_CONFIRM,
	CANCEL,
	INQUIRE_CANCEL;

	public boolean isInquiry() {
		return this == INQUIRE_CONFIRM || this == INQUIRE_CANCEL;
	}
}
