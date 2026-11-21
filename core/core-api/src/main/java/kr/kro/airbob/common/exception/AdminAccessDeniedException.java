package kr.kro.airbob.common.exception;

public class AdminAccessDeniedException extends BaseException {

	public AdminAccessDeniedException() {
		super(ErrorCode.ADMIN_ACCESS_DENIED);
	}
}
