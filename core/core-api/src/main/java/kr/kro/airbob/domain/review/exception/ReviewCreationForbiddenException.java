package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReviewCreationForbiddenException extends BaseException {

	public ReviewCreationForbiddenException() {
		super(ErrorCode.REVIEW_CREATION_DENIED);
	}
}
