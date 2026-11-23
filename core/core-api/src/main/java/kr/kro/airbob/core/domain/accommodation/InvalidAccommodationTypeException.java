package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidAccommodationTypeException extends BaseException {

	public InvalidAccommodationTypeException() {
		super(ErrorCode.ACCOMMODATION_INVALID_TYPE);
	}
}
