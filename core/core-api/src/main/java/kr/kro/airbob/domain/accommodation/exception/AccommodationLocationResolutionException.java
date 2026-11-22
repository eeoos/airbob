package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AccommodationLocationResolutionException extends BaseException {

	public AccommodationLocationResolutionException() {
		super(ErrorCode.ACCOMMODATION_LOCATION_RESOLUTION_FAILED);
	}
}
