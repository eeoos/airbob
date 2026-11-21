package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class AuthenticationRequiredException extends BaseException {

	public AuthenticationRequiredException() {
		super(ErrorCode.UNAUTHORIZED_ACCESS);
	}
}
