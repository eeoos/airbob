package kr.kro.airbob.domain.payment.entity;

public enum PaymentOperationResolutionReason {
	PROVIDER_PAYMENT_NOT_FOUND,
	PROVIDER_DASHBOARD_VERIFIED_NOT_PAID,
	SETTLEMENT_REPORT_VERIFIED_NOT_PAID
}
