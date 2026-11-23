package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponNotFoundException extends BaseException {

	public CouponNotFoundException() {
		super(ErrorCode.COUPON_NOT_FOUND);
	}
}
