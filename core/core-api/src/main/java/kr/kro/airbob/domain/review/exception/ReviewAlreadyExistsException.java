package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReviewAlreadyExistsException extends BaseException {

	public ReviewAlreadyExistsException() {
		super(ErrorCode.REVIEW_ALREADY_EXISTS);
	}
}
