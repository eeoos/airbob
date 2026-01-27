package kr.kro.airbob.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.dto.ErrorResponse;
import kr.kro.airbob.domain.accommodation.exception.PublishingFieldRequiredException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.TossErrorCode;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


	// @Valid 유효성 검사 실패 시
	@ExceptionHandler(BindException.class)
	protected ResponseEntity<ApiResponse<?>> handleBindException(BindException e) {
		log.warn("handleBindException (Validation Failed): {}", e.getMessage());
		final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, e.getBindingResult());
		return new ResponseEntity<>(ApiResponse.error(response), ErrorCode.INVALID_INPUT_VALUE.getStatus());
	}

	// Toss 관련 예외
	@ExceptionHandler(TossPaymentException.class)
	protected ResponseEntity<ApiResponse<?>> handleTossPaymentException(TossPaymentException e) {
		TossErrorCode tossErrorCode = e.getErrorCode();
		log.error("Toss Payment Error: type={}, code={}, message={}",
			tossErrorCode.getClass().getSimpleName(), tossErrorCode.name(), tossErrorCode.getErrorCode());

		final ErrorResponse response = ErrorResponse.of(ErrorCode.TOSS_PAYMENT_ERROR);
		return new ResponseEntity<>(ApiResponse.error(response), tossErrorCode.getStatusCode());
	}


	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	protected ResponseEntity<ApiResponse<?>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
		log.warn("handleMethodArgumentTypeMismatchException: Parameter '{}' requires type '{}' but value was '{}'",
			e.getName(), e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "N/A", e.getValue());

		final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_TYPE_VALUE);
		return new ResponseEntity<>(ApiResponse.error(response), ErrorCode.INVALID_TYPE_VALUE.getStatus());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<?>> handleNoResourceFoundException(NoResourceFoundException e) {
		log.warn("No Resource Found: {}", e.getResourcePath());

		// ErrorCode에 NOT_FOUND(404) 관련 코드가 없다면 만들어야 한다.
		// 임시로 ErrorResponse를 직접 만들거나, ErrorCode.NOT_FOUND_RESOURCE 등을 정의해서 써라.
		// 여기선 네가 가진 ErrorCode 구조를 존중해 예시를 든다.

		// 만약 ErrorCode에 'RESOURCE_NOT_FOUND' 같은 게 없다면 ErrorCode.INVALID_INPUT_VALUE 등을 쓰되 상태만 404로 보내라.
		// 하지만 정석은 ErrorCode.RESOURCE_NOT_FOUND(404, "R000", "리소스를 찾을 수 없습니다.")를 추가하는 것이다.
		final ErrorResponse response = ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND);

		return new ResponseEntity<>(ApiResponse.error(response), ErrorCode.RESOURCE_NOT_FOUND.getStatus());
	}

	@ExceptionHandler(MissingRequestCookieException.class)
	protected ResponseEntity<ApiResponse<?>> handleMissingRequestCookieException(MissingRequestCookieException e) {
		log.warn("handleMissingRequestCookieException: {} - Required cookie '{}' is missing.", e.getMessage(), e.getCookieName());
		final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE);
		return new ResponseEntity<>(ApiResponse.error(response), ErrorCode.INVALID_INPUT_VALUE.getStatus());
	}

	@ExceptionHandler(PublishingFieldRequiredException.class)
	protected ResponseEntity<ApiResponse<?>> handlePublishingFieldRequiredException(PublishingFieldRequiredException e) {
		log.warn("handlePublishingFieldRequiredException: field={}, reason={}", e.getFieldName(), e.getReason());
		final ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getFieldName(), e.getReason());
		return new ResponseEntity<>(ApiResponse.error(response), e.getErrorCode().getStatus());
	}

	@ExceptionHandler(BaseException.class)
	protected ResponseEntity<ApiResponse<?>> handleBaseException(final BaseException e) {
		final ErrorCode errorCode = e.getErrorCode();

		if (errorCode == ErrorCode.OUTBOX_PUBLISH_FAILED) {
			log.error("[CRITICAL] handleBaseException: Code={}, Message={}", errorCode.getCode(), errorCode.getMessage(), e);
		} else {
			log.warn("handleBaseException: Code={}, Message={}", errorCode.getCode(), errorCode.getMessage());
		}

		final ErrorResponse response = ErrorResponse.of(errorCode);
		return new ResponseEntity<>(ApiResponse.error(response), errorCode.getStatus());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
		log.error("Unhandled exception occurred", e);
		final ErrorResponse response = ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR);
		return new ResponseEntity<>(ApiResponse.error(response), ErrorCode.INTERNAL_SERVER_ERROR.getStatus());
	}
}
