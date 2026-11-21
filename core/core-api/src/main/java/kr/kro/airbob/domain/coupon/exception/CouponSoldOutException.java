package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponSoldOutException extends BaseException {

	public CouponSoldOutException() {
		super(ErrorCode.COUPON_SOLD_OUT);
	}
}
