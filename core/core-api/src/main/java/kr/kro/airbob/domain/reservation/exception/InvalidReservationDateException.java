package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidReservationDateException extends BaseException {

	public InvalidReservationDateException() {
		super(ErrorCode.INVALID_RESERVATION_DATE);
	}
}
