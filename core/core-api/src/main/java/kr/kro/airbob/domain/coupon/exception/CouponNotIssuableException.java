package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponNotIssuableException extends BaseException {

	public CouponNotIssuableException() {
		super(ErrorCode.COUPON_NOT_ISSUABLE);
	}
}
