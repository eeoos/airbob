package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationQuoteNotFoundException extends BaseException {
	public ReservationQuoteNotFoundException() {
		super(ErrorCode.RESERVATION_QUOTE_NOT_FOUND);
	}
}
