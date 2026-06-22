package kr.kro.airbob.common.code.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CommonCodeGroupNotFoundException extends BaseException {

	public CommonCodeGroupNotFoundException() {
		super(ErrorCode.COMMON_CODE_GROUP_NOT_FOUND);
	}
}
