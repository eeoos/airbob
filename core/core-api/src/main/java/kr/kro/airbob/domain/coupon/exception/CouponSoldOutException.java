package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponSoldOutException extends BaseException {

	public CouponSoldOutException() {
		super(ErrorCode.COUPON_SOLD_OUT);
	}
}
