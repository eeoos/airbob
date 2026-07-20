package kr.kro.airbob.domain.coupon.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CouponStockPreparationNotAllowedException extends BaseException {

	public CouponStockPreparationNotAllowedException() {
		super(ErrorCode.COUPON_STOCK_PREPARATION_NOT_ALLOWED);
	}
}
