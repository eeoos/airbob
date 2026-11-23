package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationCancellationDeadlinePassedException extends BaseException {

	public ReservationCancellationDeadlinePassedException() {
		super(ErrorCode.RESERVATION_CANCELLATION_DEADLINE_PASSED);
	}
}
