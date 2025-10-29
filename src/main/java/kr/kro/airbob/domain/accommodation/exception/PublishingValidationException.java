package kr.kro.airbob.domain.accommodation.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PublishingValidationException extends BaseException {

	public PublishingValidationException(String fieldName) {
		super(fieldName + "은(는) 필수 정보입니다.", ErrorCode.INVALID_INPUT_VALUE);
	}
	public PublishingValidationException(ErrorCode errorCode) {
		super(errorCode);
	}
}
