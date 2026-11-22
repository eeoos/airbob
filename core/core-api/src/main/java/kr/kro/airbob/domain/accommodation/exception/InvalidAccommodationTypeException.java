package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidAccommodationTypeException extends BaseException {

	public InvalidAccommodationTypeException() {
		super(ErrorCode.ACCOMMODATION_INVALID_TYPE);
	}
}
