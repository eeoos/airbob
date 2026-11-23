package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AccommodationLocationResolutionException extends BaseException {

	public AccommodationLocationResolutionException() {
		super(ErrorCode.ACCOMMODATION_LOCATION_RESOLUTION_FAILED);
	}
}
