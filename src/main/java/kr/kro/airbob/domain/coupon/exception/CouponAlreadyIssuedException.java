package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponAlreadyIssuedException extends BaseException {

	public CouponAlreadyIssuedException() {
		super(ErrorCode.COUPON_ALREADY_ISSUED);
	}
}
