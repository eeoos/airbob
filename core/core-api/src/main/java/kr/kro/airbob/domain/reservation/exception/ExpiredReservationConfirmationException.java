package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ExpiredReservationConfirmationException extends BaseException {

	public ExpiredReservationConfirmationException() {
		super(ErrorCode.CANNOT_CONFIRM_EXPIRED_RESERVATION);
	}
}
