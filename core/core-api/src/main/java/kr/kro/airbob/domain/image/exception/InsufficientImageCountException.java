package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InsufficientImageCountException extends BaseException {
	public InsufficientImageCountException(String message) {
		super(message, ErrorCode.ACCOMMODATION_IMAGE_COUNT_TOO_LOW);
	}
}
