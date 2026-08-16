package kr.kro.airbob.domain.payment.service;

import java.util.Objects;
import java.util.UUID;

public record PaymentOperationManualReviewNotice(UUID operationUid) {
	public PaymentOperationManualReviewNotice {
		Objects.requireNonNull(operationUid, "operationUid");
	}
}
