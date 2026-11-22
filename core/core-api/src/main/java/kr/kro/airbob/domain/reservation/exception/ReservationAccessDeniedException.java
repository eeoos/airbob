package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationAccessDeniedException extends BaseException {

	public ReservationAccessDeniedException() {
		super(ErrorCode.RESERVATION_ACCESS_DENIED);
	}
}
