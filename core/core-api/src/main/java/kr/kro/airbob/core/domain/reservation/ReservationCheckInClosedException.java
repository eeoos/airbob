package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationCheckInClosedException extends BaseException {

	public ReservationCheckInClosedException() {
		super(ErrorCode.RESERVATION_CHECK_IN_CLOSED);
	}
}
