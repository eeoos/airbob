package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationNotFoundException extends BaseException {

	public ReservationNotFoundException() {
		super(ErrorCode.PAYMENT_NOT_FOUND);
	}
}
