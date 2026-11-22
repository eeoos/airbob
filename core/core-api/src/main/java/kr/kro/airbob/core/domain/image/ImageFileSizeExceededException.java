package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageFileSizeExceededException extends BaseException {
	public ImageFileSizeExceededException() {
		super(ErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
	}
}
