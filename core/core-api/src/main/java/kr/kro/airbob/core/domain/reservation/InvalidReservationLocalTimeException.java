package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidReservationLocalTimeException extends BaseException {

	public InvalidReservationLocalTimeException() {
		super(ErrorCode.RESERVATION_LOCAL_TIME_INVALID);
	}
}
