package kr.kro.airbob.core.domain.payment;

import java.time.Instant;
import java.util.UUID;

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
