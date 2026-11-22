package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidReservationDateException extends BaseException {

	public InvalidReservationDateException() {
		super(ErrorCode.INVALID_RESERVATION_DATE);
	}
}
