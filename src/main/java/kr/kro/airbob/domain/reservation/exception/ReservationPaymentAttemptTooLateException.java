package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationPaymentAttemptTooLateException extends BaseException {

	public ReservationPaymentAttemptTooLateException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_TOO_LATE);
	}
}
