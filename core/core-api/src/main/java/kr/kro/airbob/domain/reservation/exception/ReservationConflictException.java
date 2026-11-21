package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationConflictException extends BaseException {

	public ReservationConflictException() {
		super(ErrorCode.RESERVATION_CONFLICT);
	}
}
