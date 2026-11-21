package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidAccommodationTypeException extends BaseException {

	public InvalidAccommodationTypeException() {
		super(ErrorCode.ACCOMMODATION_INVALID_TYPE);
	}
}
