package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponConfigurationLockedException extends BaseException {
	public CouponConfigurationLockedException() { super(ErrorCode.COUPON_CONFIGURATION_LOCKED); }
}
