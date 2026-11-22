package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageDeleteException extends BaseException {
	public ImageDeleteException() {
		super(ErrorCode.IMAGE_DELETE_FAILED);
	}
}
