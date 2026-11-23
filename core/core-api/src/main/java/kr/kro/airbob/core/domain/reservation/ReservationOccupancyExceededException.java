package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationOccupancyExceededException extends BaseException {

	public ReservationOccupancyExceededException() {
		super(ErrorCode.RESERVATION_OCCUPANCY_EXCEEDED);
	}
}
