package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponConfigurationLockedException extends BaseException {
	public CouponConfigurationLockedException() { super(ErrorCode.COUPON_CONFIGURATION_LOCKED); }
}
