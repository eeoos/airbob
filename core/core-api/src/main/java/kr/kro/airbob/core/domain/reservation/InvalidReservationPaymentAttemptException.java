package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidReservationPaymentAttemptException extends BaseException {

	public InvalidReservationPaymentAttemptException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_INVALID);
	}
}
