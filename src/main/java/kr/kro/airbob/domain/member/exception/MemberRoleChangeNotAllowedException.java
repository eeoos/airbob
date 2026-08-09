package kr.kro.airbob.domain.member.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class MemberRoleChangeNotAllowedException extends BaseException {

	public MemberRoleChangeNotAllowedException() {
		super(ErrorCode.MEMBER_ROLE_CHANGE_NOT_ALLOWED);
	}
}
