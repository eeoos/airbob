package kr.kro.airbob.common.code.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CommonCodeNotFoundException extends BaseException {

	public CommonCodeNotFoundException() {
		super(ErrorCode.COMMON_CODE_NOT_FOUND);
	}
}
