package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageNotFoundException extends BaseException {
	public ImageNotFoundException() {
		super(ErrorCode.IMAGE_NOT_FOUND);
	}
}
