package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class InvalidPasswordException extends BaseException {

	public InvalidPasswordException() {
		super(ErrorCode.INVALID_PASSWORD);
	}
}
