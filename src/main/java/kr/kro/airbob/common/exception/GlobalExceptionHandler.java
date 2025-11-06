package kr.kro.airbob.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
