package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationInventoryBusyException extends BaseException {
	public ReservationInventoryBusyException(Throwable cause) {
		super(cause, ErrorCode.RESERVATION_INVENTORY_BUSY);
	}
}
