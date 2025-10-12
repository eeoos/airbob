package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewUpdateForbiddenException extends BaseException {

	public ReviewUpdateForbiddenException() {
		super(ErrorCode.REVIEW_UPDATE_FORBIDDEN);
	}
}
