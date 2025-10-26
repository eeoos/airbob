package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ImageFileSizeExceededException extends BaseException {
	public ImageFileSizeExceededException() {
		super(ErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
	}
}
