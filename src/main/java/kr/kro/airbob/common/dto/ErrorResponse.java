package kr.kro.airbob.common.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.validation.BindingResult;

import kr.kro.airbob.common.exception.ErrorCode;
import lombok.Getter;

@Getter
public class ErrorResponse {

	private final String message;
	private final int status;
	private final String code;
	private final List<FieldError> errors;

	private ErrorResponse(final ErrorCode code, final List<FieldError> errors) {
		this.message = code.getMessage();
		this.status = code.getStatus().value();
		this.errors = errors;
		this.code = code.getCode();
	}

	private ErrorResponse(final ErrorCode code, final FieldError fieldError) {
		this.message = code.getMessage();
		this.status = code.getStatus().value();
		this.errors = List.of(fieldError);
		this.code = code.getCode();
	}

	// 일반 에러 처리용
	public static ErrorResponse of(final ErrorCode code) {
		return new ErrorResponse(code, new ArrayList<>());
	}

	// @Valid 에러 처리용
	public static ErrorResponse of(final ErrorCode code, final BindingResult bindingResult) {
		return new ErrorResponse(code, FieldError.of(bindingResult));
	}

	// 커스텀 필드 에러용
	public static ErrorResponse of(final ErrorCode code, String field, String reason) {
		FieldError fieldError = new FieldError(field, "N/A", reason);
		return new ErrorResponse(code, fieldError);
	}
	public record FieldError(
		String field,
		String value,
		String reason
	) {
		private static final String REDACTED_VALUE = "[REDACTED]";
		private static final List<String> SENSITIVE_FIELD_MARKERS = List.of(
			"evidencereference",
			"paymentkey",
			"password",
			"token",
			"secret",
			"credential",
			"authorization",
			"privatekey",
			"apikey"
		);

		private static List<FieldError> of(final BindingResult bindingResult) {
			final List<org.springframework.validation.FieldError> fieldErrors = bindingResult.getFieldErrors();
			return fieldErrors.stream()
				.map(FieldError::from)
				.toList();
		}

		private static FieldError from(org.springframework.validation.FieldError error) {
			if (isSensitive(error.getField())) {
				return new FieldError(error.getField(), REDACTED_VALUE, constraintCode(error));
			}
			Object rejectedValue = error.getRejectedValue();
			return new FieldError(
				error.getField(),
				rejectedValue == null ? "null" : rejectedValue.toString(),
				error.getDefaultMessage());
		}

		private static boolean isSensitive(String field) {
			String normalizedField = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
			return SENSITIVE_FIELD_MARKERS.stream().anyMatch(normalizedField::contains);
		}

		private static String constraintCode(org.springframework.validation.FieldError error) {
			return error.getCode() == null ? "Validation" : error.getCode();
		}
	}
}
