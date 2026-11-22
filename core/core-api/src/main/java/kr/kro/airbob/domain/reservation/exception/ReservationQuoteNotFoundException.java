package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationQuoteNotFoundException extends BaseException {
	public ReservationQuoteNotFoundException() {
		super(ErrorCode.RESERVATION_QUOTE_NOT_FOUND);
	}
}
