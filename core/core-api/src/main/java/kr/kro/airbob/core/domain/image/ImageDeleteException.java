package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageDeleteException extends BaseException {
	public ImageDeleteException() {
		super(ErrorCode.IMAGE_DELETE_FAILED);
	}
}
