package kr.kro.airbob.domain.commoncode.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CommonCodeDuplicateException extends BaseException {

	public CommonCodeDuplicateException() {
		super(ErrorCode.COMMON_CODE_DUPLICATE);
	}

	public CommonCodeDuplicateException(Throwable cause) {
		super(cause, ErrorCode.COMMON_CODE_DUPLICATE);
	}
}
