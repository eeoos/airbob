package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewCreationForbiddenException extends BaseException {

	public ReviewCreationForbiddenException() {
		super(ErrorCode.REVIEW_CREATION_DENIED);
	}
}
