package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class EmptyImageFileException extends BaseException {
	public EmptyImageFileException() {
		super(ErrorCode.EMPTY_IMAGE_FILE);
	}
}
