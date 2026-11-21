package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationCancellationDeadlinePassedException extends BaseException {

	public ReservationCancellationDeadlinePassedException() {
		super(ErrorCode.RESERVATION_CANCELLATION_DEADLINE_PASSED);
	}
}
