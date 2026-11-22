package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidImageFormatException extends BaseException {
	public InvalidImageFormatException() {
		super(ErrorCode.INVALID_IMAGE_FORMAT);
	}
}
