package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationInventoryBusyException extends BaseException {
	public ReservationInventoryBusyException(Throwable cause) {
		super(cause, ErrorCode.RESERVATION_INVENTORY_BUSY);
	}
}
