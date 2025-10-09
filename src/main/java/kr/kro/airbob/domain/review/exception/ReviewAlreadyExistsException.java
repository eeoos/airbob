package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewAlreadyExistsException extends BaseException {

	public ReviewAlreadyExistsException() {
		super(ErrorCode.REVIEW_ALREADY_EXISTS);
	}
}
