package kr.kro.airbob.core.domain.review;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ReviewSummaryNotFoundException extends BaseException {

	public ReviewSummaryNotFoundException() {
		super(ErrorCode.REVIEW_SUMMARY_NOT_FOUND);
	}
}
