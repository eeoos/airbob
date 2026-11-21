package kr.kro.airbob.domain.payment.service.gateway;

public sealed interface PaymentGatewayResult {

	record Approved(ConfirmedPayment payment) implements PaymentGatewayResult {
	}

	record Cancelled(CancelledPayment payment) implements PaymentGatewayResult {
	}

	record PaymentActive(String code, String message) implements PaymentGatewayResult {
	}

	record ManualReviewRequired(String code, String message) implements PaymentGatewayResult {
	}

	record Declined(String code, String message) implements PaymentGatewayResult {
	}

	record RetryableFailure(String code, String message) implements PaymentGatewayResult {
	}

	record OutcomeUnknown(String code, String message) implements PaymentGatewayResult {
	}

	record NotFound(String code, String message) implements PaymentGatewayResult {
	}
}
