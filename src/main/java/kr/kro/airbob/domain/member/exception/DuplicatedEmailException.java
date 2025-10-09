package kr.kro.airbob.domain.member.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class DuplicatedEmailException extends BaseException {

	public DuplicatedEmailException() {
		super(ErrorCode.EMAIL_DUPLICATION);
	}
}
