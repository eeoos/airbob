package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationQuoteStaleException extends BaseException {
	public ReservationQuoteStaleException() {
		super(ErrorCode.RESERVATION_QUOTE_STALE);
	}
}
