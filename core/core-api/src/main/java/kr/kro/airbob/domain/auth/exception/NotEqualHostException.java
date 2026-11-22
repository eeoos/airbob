package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class NotEqualHostException extends BaseException {

	public NotEqualHostException() {
		super(ErrorCode.HOST_ACCESS_DENIED);
	}
}
