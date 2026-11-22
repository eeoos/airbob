package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageAccessDeniedException extends BaseException {
	public ImageAccessDeniedException() {
		super(ErrorCode.IMAGE_ACCESS_DENIED);
	}
}
