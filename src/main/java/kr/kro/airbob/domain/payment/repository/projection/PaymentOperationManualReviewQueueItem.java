package kr.kro.airbob.domain.payment.repository.projection;

import java.time.Instant;
import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperationType;

public record PaymentOperationManualReviewQueueItem(
	UUID operationUid,
	PaymentOperationType operationType,
	int attemptCount,
	int manualReviewCount,
	Instant reviewRequiredAt,
	long version,
	boolean notPaidResolutionEligible
) {
}
