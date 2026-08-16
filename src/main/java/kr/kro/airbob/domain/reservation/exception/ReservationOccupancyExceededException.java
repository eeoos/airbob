package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationOccupancyExceededException extends BaseException {

	public ReservationOccupancyExceededException() {
		super(ErrorCode.RESERVATION_OCCUPANCY_EXCEEDED);
	}
}
