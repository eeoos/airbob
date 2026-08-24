package kr.kro.airbob.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.MapBindingResult;

import kr.kro.airbob.common.exception.ErrorCode;

class ErrorResponseTest {

	private static final String REJECTED_SECRET = "secret-validation-value";

	@ParameterizedTest
	@ValueSource(strings = {
		"evidenceReference",
		"paymentKey",
		"password",
		"accessToken",
		"refresh_token",
		"apiSecret",
		"credentials.authorization",
		"signingPrivateKey"
	})
	void redactsRejectedValuesForSensitiveFieldNames(String field) {
		Map<String, Object> requestValues = Map.of(field, REJECTED_SECRET);
		MapBindingResult bindingResult = new MapBindingResult(requestValues, "request");
		bindingResult.rejectValue(field, "Pattern", "must match a safe pattern");

		ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, bindingResult);

		assertThat(response.getErrors()).singleElement().satisfies(error -> {
			assertThat(error.field()).isEqualTo(field);
			assertThat(error.value()).isEqualTo("[REDACTED]");
			assertThat(error.reason()).isEqualTo("Pattern");
		});
	}

	@Test
	void preservesRejectedValueAndReasonForOrdinaryFields() {
		Map<String, Object> requestValues = Map.of("displayName", "short-name");
		MapBindingResult bindingResult = new MapBindingResult(requestValues, "request");
		bindingResult.rejectValue("displayName", "Size", "must contain at least 32 characters");

		ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, bindingResult);

		assertThat(response.getErrors()).singleElement().satisfies(error -> {
			assertThat(error.field()).isEqualTo("displayName");
			assertThat(error.value()).isEqualTo("short-name");
			assertThat(error.reason()).isEqualTo("must contain at least 32 characters");
		});
	}
}
