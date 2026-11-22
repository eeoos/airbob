package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidImageFormatException extends BaseException {
	public InvalidImageFormatException() {
		super(ErrorCode.INVALID_IMAGE_FORMAT);
	}
}
