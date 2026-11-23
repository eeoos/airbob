package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponAlreadyPreparedException extends BaseException {

	public CouponAlreadyPreparedException() {
		super(ErrorCode.COUPON_ALREADY_PREPARED);
	}
}
