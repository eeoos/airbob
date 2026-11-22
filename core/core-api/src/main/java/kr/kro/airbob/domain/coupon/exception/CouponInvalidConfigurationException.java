package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponInvalidConfigurationException extends BaseException {
	public CouponInvalidConfigurationException() { super(ErrorCode.COUPON_INVALID_CONFIGURATION); }
}
