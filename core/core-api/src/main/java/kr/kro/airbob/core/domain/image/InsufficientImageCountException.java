package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InsufficientImageCountException extends BaseException {
	public InsufficientImageCountException(String message) {
		super(message, ErrorCode.ACCOMMODATION_IMAGE_COUNT_TOO_LOW);
	}
}
