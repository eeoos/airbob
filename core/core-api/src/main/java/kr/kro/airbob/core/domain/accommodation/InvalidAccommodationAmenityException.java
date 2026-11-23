package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidAccommodationAmenityException extends BaseException {

	public InvalidAccommodationAmenityException() {
		super(ErrorCode.ACCOMMODATION_INVALID_AMENITY);
	}
}
