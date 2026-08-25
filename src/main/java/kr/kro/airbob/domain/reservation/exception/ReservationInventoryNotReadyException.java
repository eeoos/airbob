package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationInventoryNotReadyException extends BaseException {

	public ReservationInventoryNotReadyException() {
		super(ErrorCode.RESERVATION_INVENTORY_NOT_READY);
	}
}
