package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AccommodationAccessDeniedException extends BaseException {

	public AccommodationAccessDeniedException() {
		super(ErrorCode.ACCOMMODATION_ACCESS_DENIED);
	}
}
