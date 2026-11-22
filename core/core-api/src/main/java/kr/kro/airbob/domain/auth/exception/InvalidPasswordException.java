package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class InvalidPasswordException extends BaseException {

	public InvalidPasswordException() {
		super(ErrorCode.INVALID_PASSWORD);
	}
}
