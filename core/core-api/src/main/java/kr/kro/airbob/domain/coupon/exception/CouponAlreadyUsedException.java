package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponAlreadyUsedException extends BaseException {

	public CouponAlreadyUsedException() {
		super(ErrorCode.COUPON_ALREADY_USED);
	}
}
