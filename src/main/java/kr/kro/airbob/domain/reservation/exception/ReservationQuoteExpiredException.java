package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationQuoteExpiredException extends BaseException {
	public ReservationQuoteExpiredException() {
		super(ErrorCode.RESERVATION_QUOTE_EXPIRED);
	}
}
