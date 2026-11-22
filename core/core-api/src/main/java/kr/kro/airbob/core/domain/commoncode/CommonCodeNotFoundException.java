package kr.kro.airbob.core.domain.commoncode;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CommonCodeNotFoundException extends BaseException {

	public CommonCodeNotFoundException() {
		super(ErrorCode.COMMON_CODE_NOT_FOUND);
	}
}
