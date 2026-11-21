package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;

public record PaymentOperationManualReviewResult(
	UUID operationUid,
	PaymentOperationStatus status,
	long version
) {
	public static PaymentOperationManualReviewResult from(PaymentOperation operation) {
		return new PaymentOperationManualReviewResult(
			operation.getOperationUid(), operation.getStatus(), operation.getVersion());
	}
}
