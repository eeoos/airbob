package kr.kro.airbob.common.code.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CommonCodeDuplicateException extends BaseException {

	public CommonCodeDuplicateException() {
		super(ErrorCode.COMMON_CODE_DUPLICATE);
	}
}
