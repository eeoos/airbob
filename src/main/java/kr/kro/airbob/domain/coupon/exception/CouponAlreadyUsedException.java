package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponAlreadyUsedException extends BaseException {

	public CouponAlreadyUsedException() {
		super(ErrorCode.COUPON_ALREADY_USED);
	}
}
