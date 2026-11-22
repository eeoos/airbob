package kr.kro.airbob.domain.commoncode.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CommonCodeNotFoundException extends BaseException {

	public CommonCodeNotFoundException() {
		super(ErrorCode.COMMON_CODE_NOT_FOUND);
	}
}
