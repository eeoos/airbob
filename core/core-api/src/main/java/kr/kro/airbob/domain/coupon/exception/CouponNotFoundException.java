package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponNotFoundException extends BaseException {

	public CouponNotFoundException() {
		super(ErrorCode.COUPON_NOT_FOUND);
	}
}
