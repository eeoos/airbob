package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReviewUpdateForbiddenException extends BaseException {

	public ReviewUpdateForbiddenException() {
		super(ErrorCode.REVIEW_UPDATE_FORBIDDEN);
	}
}
