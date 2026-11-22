package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponNotApplicableException extends BaseException {

	public CouponNotApplicableException() {
		super(ErrorCode.COUPON_NOT_APPLICABLE);
	}
}
