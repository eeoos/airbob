package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidAccommodationAmenityException extends BaseException {

	public InvalidAccommodationAmenityException() {
		super(ErrorCode.ACCOMMODATION_INVALID_AMENITY);
	}
}
