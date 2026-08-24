package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;

@Service
public class PaymentOperationManualReviewCommandService {

	// Kept in a separate proxied bean so its transaction commits before the fence is released.
	private final PaymentOperationManualReviewTransactionService transactionService;
	private final PaymentOperationExecutionFence executionFence;

	public PaymentOperationManualReviewCommandService(
		PaymentOperationManualReviewTransactionService transactionService,
		PaymentOperationExecutionFence executionFence
	) {
		this.transactionService = transactionService;
		this.executionFence = executionFence;
	}

	public PaymentOperationManualReviewResult requestReconciliation(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion
	) {
		// This command only queues an inquiry. The executor acquires the same fence before
		// claiming that inquiry, so it cannot produce not-paid evidence beside an older command.
		return transactionService.requestReconciliation(
			operationUid, actorMemberId, expectedVersion);
	}

	public PaymentOperationManualReviewResult markNotPaid(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion,
		PaymentOperationResolutionReason reasonCode,
		String evidenceReference
	) {
		return executionFence.execute(operationUid, () -> transactionService.markNotPaid(
			operationUid,
			actorMemberId,
			expectedVersion,
			reasonCode,
			evidenceReference));
	}
}
