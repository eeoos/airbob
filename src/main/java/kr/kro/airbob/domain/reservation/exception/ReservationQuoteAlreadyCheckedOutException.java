package kr.kro.airbob.domain.reservation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReservationQuoteAlreadyCheckedOutException extends BaseException {
	public ReservationQuoteAlreadyCheckedOutException() {
		super(ErrorCode.RESERVATION_QUOTE_ALREADY_CHECKED_OUT);
	}
}
