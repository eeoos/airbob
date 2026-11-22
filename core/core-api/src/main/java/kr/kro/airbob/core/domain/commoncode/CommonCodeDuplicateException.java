package kr.kro.airbob.core.domain.commoncode;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CommonCodeDuplicateException extends BaseException {

	public CommonCodeDuplicateException() {
		super(ErrorCode.COMMON_CODE_DUPLICATE);
	}

	public CommonCodeDuplicateException(Throwable cause) {
		super(cause, ErrorCode.COMMON_CODE_DUPLICATE);
	}
}
