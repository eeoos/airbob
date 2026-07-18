package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponLockTimeoutException extends BaseException {

	public CouponLockTimeoutException() {
		super(ErrorCode.COUPON_LOCK_TIMEOUT);
	}
}
