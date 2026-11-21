package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ExpiredReservationConfirmationException extends BaseException {

	public ExpiredReservationConfirmationException() {
		super(ErrorCode.CANNOT_CONFIRM_EXPIRED_RESERVATION);
	}
}
