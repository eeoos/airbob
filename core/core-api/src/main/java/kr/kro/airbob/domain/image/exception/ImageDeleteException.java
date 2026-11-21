package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ImageDeleteException extends BaseException {
	public ImageDeleteException() {
		super(ErrorCode.IMAGE_DELETE_FAILED);
	}
}
