package kr.kro.airbob.common.exception;

import java.util.stream.Collectors;

import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.auth.exception.NotEqualHostException;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import kr.kro.airbob.cursor.exception.CursorEncodingException;
import kr.kro.airbob.cursor.exception.CursorPageSizeException;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentCancelException;
import kr.kro.airbob.domain.payment.exception.TossPaymentConfirmException;
import kr.kro.airbob.domain.payment.exception.VirtualAccountIssueException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationStatusException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.review.ReviewSortType;
import kr.kro.airbob.domain.review.exception.ReviewSummaryNotFoundException;
import kr.kro.airbob.domain.review.ReviewSortType;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationNotFoundException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Void> handleEnumBindingError(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest().build();
	}

	@ExceptionHandler(AccommodationNotFoundException.class)
	public ResponseEntity<Void> handleAccommodationNotFoundException(AccommodationNotFoundException e) {
		log.error("AccommodationNotFoundException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.build();
	}

	@ExceptionHandler(ReviewSummaryNotFoundException.class)
	public ResponseEntity<Void> handleReviewSummaryNotFoundException(ReviewSummaryNotFoundException e) {
		log.error("ReviewSummaryNotFoundException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.build();
	}

	@ExceptionHandler(MemberNotFoundException.class)
	public ResponseEntity<Void> handleMemberNotFoundException(MemberNotFoundException e) {
		log.error("MemberNotFoundException: {}", e.getMessage());
		return ResponseEntity
			.status(HttpStatus.NOT_FOUND)
			.build();
	}

	@ExceptionHandler(WishlistNotFoundException.class)
	public ResponseEntity<Void> handleWishlistNotFoundException(WishlistNotFoundException e) {
		log.error("WishlistNotFoundException: {}", e.getMessage());
		return ResponseEntity
			.status(HttpStatus.NOT_FOUND)
			.build();
	}
	@ExceptionHandler(WishlistAccessDeniedException.class)
	public ResponseEntity<Void> handleWishlistAccessDeniedException(WishlistAccessDeniedException e) {
		log.warn("WishlistAccessDeniedException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@ExceptionHandler(CursorEncodingException.class)
	public ResponseEntity<Void> handleCursorEncodingException(CursorEncodingException e) {
		log.error("Cursor encoding fail: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
	}

	@ExceptionHandler(CursorPageSizeException.class)
	public ResponseEntity<Void> handleCursorPageSizeException(CursorPageSizeException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
	}

	@ExceptionHandler(WishlistAccommodationAccessDeniedException.class)
	public ResponseEntity<Void> handleWishlistAccommodationAccessDeniedException(
		WishlistAccommodationAccessDeniedException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@ExceptionHandler(WishlistAccommodationNotFoundException.class)
	public ResponseEntity<Void> handleWishlistAccommodationNotFoundException(
		WishlistAccommodationNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	@ExceptionHandler(MethodArgumentNotValidException.class) // @Valid에서 발생하는 에러
	public ResponseEntity<Void> handleValidationExceptions(MethodArgumentNotValidException e) {
		String errorMessage = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(FieldError::getDefaultMessage)
			.collect(Collectors.joining(", "));

		log.error("Bean Validation error(@Valid): {}", errorMessage);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
	}

	@ExceptionHandler(DuplicatedEmailException.class)
	public ResponseEntity<Void> handleDuplicatedEmailException(DuplicatedEmailException e) {
		log.error("email duplicated: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}

	@ExceptionHandler(NotEqualHostException.class)
	public ResponseEntity<Void> handleNotEqualHostException(NotEqualHostException e) {
		log.error("NotEqualHostException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@ExceptionHandler(InvalidReservationStatusException.class)
	public ResponseEntity<Void> handleInvalidReservationStatusException(InvalidReservationStatusException e) {
		log.error("InvalidReservationStatusException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}

	@ExceptionHandler(ReservationConflictException.class)
	public ResponseEntity<Void> handleReservationConflictException(ReservationConflictException e) {
		log.error("ReservationConflictException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}

	@ExceptionHandler(ReservationNotFoundException.class)
	public ResponseEntity<Void> handleReservationNotFoundException(ReservationNotFoundException e) {
		log.error("ReservationNotFoundException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	@ExceptionHandler(ReservationLockException.class)
	public ResponseEntity<Void> handleReservationLockException(ReservationLockException e) {
		log.error("ReservationLockException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}

	@ExceptionHandler(TossPaymentCancelException.class)
	public ResponseEntity<Void> handleTossPaymentCancelException(TossPaymentCancelException e) {
		log.error("TossPaymentCancelException: {}", e.getMessage());
		return ResponseEntity.status(e.getErrorCode().getStatusCode()).build();
	}

	@ExceptionHandler(TossPaymentConfirmException.class)
	public ResponseEntity<Void> handleTossPaymentConfirmException(TossPaymentConfirmException e) {
		log.error("TossPaymentConfirmException: {}", e.getMessage());
		return ResponseEntity.status(e.getErrorCode().getStatusCode()).build();
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	public ResponseEntity<Void> handlePaymentNotFoundException(PaymentNotFoundException e) {
		log.error("PaymentNotFoundException: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	@ExceptionHandler(VirtualAccountIssueException.class)
	public ResponseEntity<Void> handleVirtualAccountIssueException(VirtualAccountIssueException e) {
		log.error("VirtualAccountIssueException: {}", e.getMessage());
		return ResponseEntity.status(e.getErrorCode().getStatusCode()).build();
	}



	@ExceptionHandler(Exception.class)
	public ResponseEntity<Void> handleExceptions(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
	}
}
