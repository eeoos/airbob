package kr.kro.airbob.core.domain.reservation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReservationQuoteAlreadyCheckedOutException extends BaseException {
	public ReservationQuoteAlreadyCheckedOutException() {
		super(ErrorCode.RESERVATION_QUOTE_ALREADY_CHECKED_OUT);
	}
}
