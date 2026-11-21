package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewAccessDeniedException extends BaseException {

	public ReviewAccessDeniedException() {
		super(ErrorCode.REVIEW_ACCESS_DENIED);
	}
}
