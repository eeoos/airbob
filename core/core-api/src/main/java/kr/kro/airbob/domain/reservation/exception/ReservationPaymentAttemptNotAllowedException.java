package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationPaymentAttemptNotAllowedException extends BaseException {

	public ReservationPaymentAttemptNotAllowedException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_NOT_ALLOWED);
	}
}
