package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationCheckInClosedException extends BaseException {

	public ReservationCheckInClosedException() {
		super(ErrorCode.RESERVATION_CHECK_IN_CLOSED);
	}
}
