package kr.kro.airbob.domain.member.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class DuplicatedEmailException extends BaseException {

	public DuplicatedEmailException() {
		super(ErrorCode.EMAIL_DUPLICATION);
	}

	public DuplicatedEmailException(Throwable cause) {
		super(cause, ErrorCode.EMAIL_DUPLICATION);
	}
}
