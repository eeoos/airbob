package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class NotEqualHostException extends BaseException {

	public NotEqualHostException() {
		super(ErrorCode.HOST_ACCESS_DENIED);
	}
}
