package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationCheckoutIdempotencyConflictException extends BaseException {
	public ReservationCheckoutIdempotencyConflictException() {
		super(ErrorCode.RESERVATION_CHECKOUT_IDEMPOTENCY_CONFLICT);
	}
}
