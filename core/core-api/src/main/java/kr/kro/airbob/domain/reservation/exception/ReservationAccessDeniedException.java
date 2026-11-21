package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationAccessDeniedException extends BaseException {

	public ReservationAccessDeniedException() {
		super(ErrorCode.RESERVATION_ACCESS_DENIED);
	}
}
