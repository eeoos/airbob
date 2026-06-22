package kr.kro.airbob.domain.auth.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class AdminAccessDeniedException extends BaseException {

	public AdminAccessDeniedException() {
		super(ErrorCode.ADMIN_ACCESS_DENIED);
	}
}
