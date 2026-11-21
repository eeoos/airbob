package kr.kro.airbob.domain.commoncode.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CommonCodeGroupDuplicateException extends BaseException {

	public CommonCodeGroupDuplicateException() {
		super(ErrorCode.COMMON_CODE_GROUP_DUPLICATE);
	}

	public CommonCodeGroupDuplicateException(Throwable cause) {
		super(cause, ErrorCode.COMMON_CODE_GROUP_DUPLICATE);
	}
}
