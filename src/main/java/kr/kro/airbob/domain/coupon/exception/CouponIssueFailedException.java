package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponIssueFailedException extends BaseException {

	public CouponIssueFailedException() {
		super(ErrorCode.COUPON_ISSUE_FAILED);
	}
}
