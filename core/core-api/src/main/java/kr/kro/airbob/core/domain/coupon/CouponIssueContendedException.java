package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponIssueContendedException extends BaseException {
	public CouponIssueContendedException() {
		super(ErrorCode.COUPON_ISSUE_CONTENDED);
	}
}
