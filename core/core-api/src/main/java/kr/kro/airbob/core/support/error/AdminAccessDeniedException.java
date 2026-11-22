package kr.kro.airbob.core.support.error;

public class AdminAccessDeniedException extends BaseException {

	public AdminAccessDeniedException() {
		super(ErrorCode.ADMIN_ACCESS_DENIED);
	}
}
