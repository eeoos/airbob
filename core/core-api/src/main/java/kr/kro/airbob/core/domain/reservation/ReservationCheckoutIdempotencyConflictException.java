package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationCheckoutIdempotencyConflictException extends BaseException {
	public ReservationCheckoutIdempotencyConflictException() {
		super(ErrorCode.RESERVATION_CHECKOUT_IDEMPOTENCY_CONFLICT);
	}
}
