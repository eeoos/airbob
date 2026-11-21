package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponNotFoundException extends BaseException {

	public CouponNotFoundException() {
		super(ErrorCode.COUPON_NOT_FOUND);
	}
}
