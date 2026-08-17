package kr.kro.airbob.domain.payment.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentConfirmationFailureClassifierTest {

	private final PaymentConfirmationFailureClassifier classifier = new PaymentConfirmationFailureClassifier();

	@ParameterizedTest
	@ValueSource(strings = {
		"EXCEED_MAX_CARD_INSTALLMENT_PLAN",
		"NOT_ALLOWED_POINT_USE",
		"INVALID_REJECT_CARD",
		"BELOW_MINIMUM_AMOUNT",
		"INVALID_CARD_EXPIRATION",
		"INVALID_STOPPED_CARD",
		"EXCEED_MAX_DAILY_PAYMENT_COUNT",
		"NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT",
		"INVALID_CARD_INSTALLMENT_PLAN",
		"NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
		"EXCEED_MAX_PAYMENT_AMOUNT",
		"INVALID_CARD_LOST_OR_STOLEN",
		"RESTRICTED_TRANSFER_ACCOUNT",
		"INVALID_CARD_NUMBER",
		"EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT",
		"EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
		"EXCEED_MAX_AMOUNT",
		"INVALID_ACCOUNT_INFO_RE_REGISTER",
		"NOT_AVAILABLE_PAYMENT",
		"UNAPPROVED_ORDER_ID",
		"EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
		"REJECT_ACCOUNT_PAYMENT",
		"REJECT_CARD_PAYMENT",
		"REJECT_CARD_COMPANY",
		"REJECT_TOSSPAY_INVALID_ACCOUNT",
		"EXCEED_MAX_AUTH_COUNT",
		"EXCEED_MAX_ONE_DAY_AMOUNT",
		"NOT_AVAILABLE_BANK",
		"INVALID_PASSWORD",
		"FDS_ERROR",
		"NOT_FOUND_PAYMENT",
		"NOT_FOUND_PAYMENT_SESSION"
	})
	void allowListedCustomerDeclinesAreFinal(String code) {
		PaymentGatewayResult result = classifier.classify(code, "raw provider message");

		assertThat(result).isInstanceOfSatisfying(PaymentGatewayResult.Declined.class, decline -> {
			assertThat(decline.code()).isEqualTo(code);
			assertThat(decline.message()).doesNotContain("raw provider message");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"ALREADY_PROCESSED_PAYMENT",
		"PROVIDER_ERROR",
		"INVALID_API_KEY",
		"IDEMPOTENT_REQUEST_PROCESSING",
		"SOMETHING_NEW"
	})
	void ambiguousOrUnknownCodesRequireInquiry(String code) {
		PaymentGatewayResult result = classifier.classify(code, "raw provider message");

		assertThat(result).isInstanceOfSatisfying(PaymentGatewayResult.OutcomeUnknown.class, unknown -> {
			assertThat(unknown.code()).isEqualTo(code);
			assertThat(unknown.message()).doesNotContain("raw provider message");
		});
	}
}
