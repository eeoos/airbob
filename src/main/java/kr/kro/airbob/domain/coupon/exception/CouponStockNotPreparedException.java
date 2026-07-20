package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponStockNotPreparedException extends BaseException {

	public CouponStockNotPreparedException() {
		super(ErrorCode.COUPON_STOCK_NOT_PREPARED);
	}
}
