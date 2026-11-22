package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationPaymentAttemptTooLateException extends BaseException {

	public ReservationPaymentAttemptTooLateException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_TOO_LATE);
	}
}
