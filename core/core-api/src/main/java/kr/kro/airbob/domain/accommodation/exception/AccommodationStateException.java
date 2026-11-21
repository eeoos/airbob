package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class AccommodationStateException extends BaseException {

	public AccommodationStateException() {
		super(ErrorCode.ACCOMMODATION_NOT_PUBLISHED);
	}
}
