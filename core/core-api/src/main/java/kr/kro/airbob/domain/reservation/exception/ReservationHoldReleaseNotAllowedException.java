package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationHoldReleaseNotAllowedException extends BaseException {

	public ReservationHoldReleaseNotAllowedException() {
		super(ErrorCode.RESERVATION_HOLD_RELEASE_NOT_ALLOWED);
	}
}
