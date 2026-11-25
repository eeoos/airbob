package kr.kro.airbob.core.domain.reservation;

import static kr.kro.airbob.core.support.error.ErrorCode.*;

import kr.kro.airbob.core.support.error.BaseException;

public class ReservationStateChangeException extends BaseException {

	public ReservationStateChangeException() {
		super(RESERVATION_STATE_CHANGE_FAILED);
	}

	public ReservationStateChangeException(Throwable cause) {
		super(cause, RESERVATION_STATE_CHANGE_FAILED);
	}
}
