package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AuthenticationRequiredException extends BaseException {

	public AuthenticationRequiredException() {
		super(ErrorCode.UNAUTHORIZED_ACCESS);
	}
}
