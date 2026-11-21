package kr.kro.airbob.domain.payment.entity;

public enum PaymentOperationResolutionAction {
	RECONCILIATION_REQUESTED,
	RECONCILIATION_APPLIED,
	RECONCILIATION_DECLINED,
	RECONCILIATION_RETURNED_TO_REVIEW,
	MARKED_NOT_PAID
}
