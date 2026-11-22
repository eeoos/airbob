package kr.kro.airbob.core.domain.image;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class EmptyImageFileException extends BaseException {
	public EmptyImageFileException() {
		super(ErrorCode.EMPTY_IMAGE_FILE);
	}
}
