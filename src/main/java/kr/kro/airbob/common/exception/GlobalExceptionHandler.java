package kr.kro.airbob.common.exception;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.dto.ErrorResponse;
import kr.kro.airbob.domain.accommodation.exception.PublishingFieldRequiredException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.TossErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


	// @Valid 유효성 검사 실패 시
	@ExceptionHandler(MethodArgumentNotValidException.class)
	protected ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
		log.warn("handleMethodArgumentNotValidException: {}", e.getMessage());
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
