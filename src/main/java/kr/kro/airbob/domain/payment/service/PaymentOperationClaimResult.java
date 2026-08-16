package kr.kro.airbob.domain.payment.service;

import java.util.Objects;
import java.util.Optional;

public record PaymentOperationClaimResult(
	Optional<PaymentExecution> execution,
	Optional<PaymentOperationManualReviewNotice> manualReviewNotice
) {
	private static final PaymentOperationClaimResult NO_ACTION =
		new PaymentOperationClaimResult(Optional.empty(), Optional.empty());

	public PaymentOperationClaimResult {
		Objects.requireNonNull(execution, "execution");
		Objects.requireNonNull(manualReviewNotice, "manualReviewNotice");
		if (execution.isPresent() && manualReviewNotice.isPresent()) {
			throw new IllegalArgumentException("A claim cannot execute and enter manual review together.");
		}
	}

	public static PaymentOperationClaimResult claimed(PaymentExecution execution) {
		return new PaymentOperationClaimResult(
			Optional.of(Objects.requireNonNull(execution, "execution")), Optional.empty());
	}

	public static PaymentOperationClaimResult manualReview(
		PaymentOperationManualReviewNotice notice
	) {
		return new PaymentOperationClaimResult(
			Optional.empty(), Optional.of(Objects.requireNonNull(notice, "notice")));
	}

	public static PaymentOperationClaimResult noAction() {
		return NO_ACTION;
	}
}
