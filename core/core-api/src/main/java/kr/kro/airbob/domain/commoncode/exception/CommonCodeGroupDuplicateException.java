package kr.kro.airbob.domain.commoncode.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CommonCodeGroupDuplicateException extends BaseException {

	public CommonCodeGroupDuplicateException() {
		super(ErrorCode.COMMON_CODE_GROUP_DUPLICATE);
	}

	public CommonCodeGroupDuplicateException(Throwable cause) {
		super(cause, ErrorCode.COMMON_CODE_GROUP_DUPLICATE);
	}
}
