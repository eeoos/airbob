package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponAlreadyPreparedException extends BaseException {

	public CouponAlreadyPreparedException() {
		super(ErrorCode.COUPON_ALREADY_PREPARED);
	}
}
