package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewNotFoundException extends BaseException {

	public ReviewNotFoundException() {
		super(ErrorCode.REVIEW_NOT_FOUND);
	}
}
