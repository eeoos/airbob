package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidReservationStatusException extends BaseException {

	public InvalidReservationStatusException() {
		super(ErrorCode.RESERVATION_CONFLICT);
	}

	public InvalidReservationStatusException(ErrorCode errorCode) {
		super(errorCode);
	}
}
