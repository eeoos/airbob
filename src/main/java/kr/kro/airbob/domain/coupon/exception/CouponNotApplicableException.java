package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponNotApplicableException extends BaseException {

	public CouponNotApplicableException() {
		super(ErrorCode.COUPON_NOT_APPLICABLE);
	}
}
