package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponStockNotPreparedException extends BaseException {

	public CouponStockNotPreparedException() {
		super(ErrorCode.COUPON_STOCK_NOT_PREPARED);
	}
}
