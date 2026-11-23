package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponNotIssuableException extends BaseException {

	public CouponNotIssuableException() {
		super(ErrorCode.COUPON_NOT_ISSUABLE);
	}
}
