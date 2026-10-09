package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponIssueContendedException extends BaseException {
	public CouponIssueContendedException() { super(ErrorCode.COUPON_ISSUE_CONTENDED); }
}
