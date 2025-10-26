package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidImageFormatException extends BaseException {
	public InvalidImageFormatException() {
		super(ErrorCode.INVALID_IMAGE_FORMAT);
	}
}
