package kr.kro.airbob.core.domain.review;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReviewAccessDeniedException extends BaseException {

	public ReviewAccessDeniedException() {
		super(ErrorCode.REVIEW_ACCESS_DENIED);
	}
}
