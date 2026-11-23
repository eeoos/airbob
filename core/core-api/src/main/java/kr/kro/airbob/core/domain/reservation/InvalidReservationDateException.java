package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidReservationDateException extends BaseException {

	public InvalidReservationDateException() {
		super(ErrorCode.INVALID_RESERVATION_DATE);
	}
}
