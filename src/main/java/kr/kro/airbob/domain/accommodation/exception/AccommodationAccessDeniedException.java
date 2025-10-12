package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class AccommodationAccessDeniedException extends BaseException {

	public AccommodationAccessDeniedException() {
		super(ErrorCode.ACCOMMODATION_ACCESS_DENIED);
	}
}
