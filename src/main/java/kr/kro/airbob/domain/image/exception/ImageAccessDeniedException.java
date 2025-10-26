package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ImageAccessDeniedException extends BaseException {
	public ImageAccessDeniedException() {
		super(ErrorCode.IMAGE_ACCESS_DENIED);
	}
}
