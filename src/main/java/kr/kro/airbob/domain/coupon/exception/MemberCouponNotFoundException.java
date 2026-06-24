package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class MemberCouponNotFoundException extends BaseException {

	public MemberCouponNotFoundException() {
		super(ErrorCode.MEMBER_COUPON_NOT_FOUND);
	}
}
