package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidReservationPaymentAttemptException extends BaseException {

	public InvalidReservationPaymentAttemptException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_INVALID);
	}
}
