package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class EmptyImageFileException extends BaseException {
	public EmptyImageFileException() {
		super(ErrorCode.EMPTY_IMAGE_FILE);
	}
}
