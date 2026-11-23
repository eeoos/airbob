package kr.kro.airbob.core.domain.payment;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PaymentOperationManualReviewCommandService {

	// Kept in a separate proxied bean so its transaction commits before the fence is released.
	private final PaymentOperationManualReviewProcessor manualReviewProcessor;
	private final PaymentOperationExecutionFence executionFence;

	public PaymentOperationManualReviewCommandService(
		PaymentOperationManualReviewProcessor manualReviewProcessor,
		PaymentOperationExecutionFence executionFence
	) {
		this.manualReviewProcessor = manualReviewProcessor;
		this.executionFence = executionFence;
	}

	public PaymentOperationManualReviewResult requestReconciliation(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion
	) {
		// This command only queues an inquiry. The executor acquires the same fence before
		// claiming that inquiry, so it cannot produce not-paid evidence beside an older command.
		return manualReviewProcessor.requestReconciliation(
			operationUid, actorMemberId, expectedVersion);
	}

	public PaymentOperationManualReviewResult markNotPaid(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion,
		PaymentOperationResolutionReason reasonCode,
		String evidenceReference
	) {
		return executionFence.execute(operationUid, () -> manualReviewProcessor.markNotPaid(
			operationUid,
			actorMemberId,
			expectedVersion,
			reasonCode,
			evidenceReference));
	}
}
