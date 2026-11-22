package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationInventoryNotReadyException extends BaseException {

	public ReservationInventoryNotReadyException() {
		super(ErrorCode.RESERVATION_INVENTORY_NOT_READY);
	}
}
