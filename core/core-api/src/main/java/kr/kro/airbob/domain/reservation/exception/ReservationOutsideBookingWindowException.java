package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationOutsideBookingWindowException extends BaseException {

	public ReservationOutsideBookingWindowException() {
		super(ErrorCode.RESERVATION_OUTSIDE_BOOKING_WINDOW);
	}
}
