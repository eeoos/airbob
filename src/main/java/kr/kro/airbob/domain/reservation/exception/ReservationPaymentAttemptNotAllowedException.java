package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationPaymentAttemptNotAllowedException extends BaseException {

	public ReservationPaymentAttemptNotAllowedException() {
		super(ErrorCode.RESERVATION_PAYMENT_ATTEMPT_NOT_ALLOWED);
	}
}
