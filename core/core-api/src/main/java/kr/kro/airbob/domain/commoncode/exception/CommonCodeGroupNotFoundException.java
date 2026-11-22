package kr.kro.airbob.domain.commoncode.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CommonCodeGroupNotFoundException extends BaseException {

	public CommonCodeGroupNotFoundException() {
		super(ErrorCode.COMMON_CODE_GROUP_NOT_FOUND);
	}
}
