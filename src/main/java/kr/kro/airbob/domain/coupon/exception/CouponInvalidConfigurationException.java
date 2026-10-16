package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponInvalidConfigurationException extends BaseException {
	public CouponInvalidConfigurationException() { super(ErrorCode.COUPON_INVALID_CONFIGURATION); }
}
