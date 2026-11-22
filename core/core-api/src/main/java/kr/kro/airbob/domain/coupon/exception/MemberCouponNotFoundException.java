package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class MemberCouponNotFoundException extends BaseException {

	public MemberCouponNotFoundException() {
		super(ErrorCode.MEMBER_COUPON_NOT_FOUND);
	}
}
