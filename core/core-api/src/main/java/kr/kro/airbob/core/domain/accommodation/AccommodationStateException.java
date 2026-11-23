package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AccommodationStateException extends BaseException {

	public AccommodationStateException() {
		super(ErrorCode.ACCOMMODATION_NOT_PUBLISHED);
	}
}
