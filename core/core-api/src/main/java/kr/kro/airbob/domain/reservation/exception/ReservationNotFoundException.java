package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationNotFoundException extends BaseException {

	public ReservationNotFoundException() {
		super(ErrorCode.RESERVATION_NOT_FOUND);
	}
}
