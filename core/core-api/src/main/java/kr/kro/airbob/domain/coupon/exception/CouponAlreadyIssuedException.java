package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponAlreadyIssuedException extends BaseException {

	public CouponAlreadyIssuedException() {
		super(ErrorCode.COUPON_ALREADY_ISSUED);
	}
}
