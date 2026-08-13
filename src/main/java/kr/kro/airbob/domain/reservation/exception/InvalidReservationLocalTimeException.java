package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidReservationLocalTimeException extends BaseException {

	public InvalidReservationLocalTimeException() {
		super(ErrorCode.RESERVATION_LOCAL_TIME_INVALID);
	}
}
