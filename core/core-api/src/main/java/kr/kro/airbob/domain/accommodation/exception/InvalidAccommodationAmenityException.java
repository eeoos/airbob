package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidAccommodationAmenityException extends BaseException {

	public InvalidAccommodationAmenityException() {
		super(ErrorCode.ACCOMMODATION_INVALID_AMENITY);
	}
}
