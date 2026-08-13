package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class AccommodationLocationResolutionException extends BaseException {

	public AccommodationLocationResolutionException() {
		super(ErrorCode.ACCOMMODATION_LOCATION_RESOLUTION_FAILED);
	}
}
