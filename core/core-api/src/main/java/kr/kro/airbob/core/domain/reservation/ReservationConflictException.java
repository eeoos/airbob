package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationConflictException extends BaseException {

	public ReservationConflictException() {
		super(ErrorCode.RESERVATION_CONFLICT);
	}
}
