package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ImageNotFoundException extends BaseException {
	public ImageNotFoundException() {
		super(ErrorCode.IMAGE_NOT_FOUND);
	}
}
