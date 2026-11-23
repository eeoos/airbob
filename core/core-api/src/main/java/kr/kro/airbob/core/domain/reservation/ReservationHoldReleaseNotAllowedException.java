package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationHoldReleaseNotAllowedException extends BaseException {

	public ReservationHoldReleaseNotAllowedException() {
		super(ErrorCode.RESERVATION_HOLD_RELEASE_NOT_ALLOWED);
	}
}
