package kr.kro.airbob.domain.member.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class MemberRoleChangeNotAllowedException extends BaseException {

	public MemberRoleChangeNotAllowedException() {
		super(ErrorCode.MEMBER_ROLE_CHANGE_NOT_ALLOWED);
	}
}
