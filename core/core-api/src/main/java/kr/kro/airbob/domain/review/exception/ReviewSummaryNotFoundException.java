package kr.kro.airbob.domain.review.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ReviewSummaryNotFoundException extends BaseException {

	public ReviewSummaryNotFoundException() {
		super(ErrorCode.REVIEW_SUMMARY_NOT_FOUND);
	}
}
