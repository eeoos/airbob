package kr.kro.airbob.domain.reservation.exception;

import static kr.kro.airbob.common.exception.ErrorCode.*;

import kr.kro.airbob.common.exception.BaseException;

public class ReservationStateChangeException extends BaseException {

	public ReservationStateChangeException() {
		super(RESERVATION_STATE_CHANGE_FAILED);
	}
}
