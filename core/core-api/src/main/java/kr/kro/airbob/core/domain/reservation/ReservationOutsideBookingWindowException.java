package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationOutsideBookingWindowException extends BaseException {

	public ReservationOutsideBookingWindowException() {
		super(ErrorCode.RESERVATION_OUTSIDE_BOOKING_WINDOW);
	}
}
