package kr.kro.airbob.domain.payment.service.gateway;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public final class PaymentConfirmationFailureClassifier {

	private static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";
	private static final String DECLINED_MESSAGE = "결제가 거절되었습니다. 결제 수단을 확인해주세요.";
	private static final String OUTCOME_UNKNOWN_MESSAGE = "결제 결과를 확인하고 있습니다.";

	private static final Set<String> FINAL_DECLINE_CODES = Set.of(
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
	);

	public PaymentGatewayResult classify(String code, String ignoredProviderMessage) {
		String normalizedCode = code == null || code.isBlank() ? UNKNOWN_ERROR : code;
		if (FINAL_DECLINE_CODES.contains(normalizedCode)) {
			return new PaymentGatewayResult.Declined(normalizedCode, DECLINED_MESSAGE);
		}

		return new PaymentGatewayResult.OutcomeUnknown(normalizedCode, OUTCOME_UNKNOWN_MESSAGE);
	}
}
