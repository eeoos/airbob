package kr.kro.airbob.core.domain.payment;

import java.util.UUID;

public record PaymentOperationManualReviewResult(
	UUID operationUid,
	PaymentOperationStatus status,
	long version) {
	public static PaymentOperationManualReviewResult from(PaymentOperation operation) {
		return new PaymentOperationManualReviewResult(
			operation.getOperationUid(), operation.getStatus(), operation.getVersion());
	}
}
