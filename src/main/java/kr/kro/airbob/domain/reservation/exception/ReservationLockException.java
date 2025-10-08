package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationLockException extends BaseException {

	public ReservationLockException() {
		super(ErrorCode.RESERVATION_LOCK_FAILED);
	}
}
