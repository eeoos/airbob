package kr.kro.airbob.core.domain.coupon;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CouponStockPreparationNotAllowedException extends BaseException {

	public CouponStockPreparationNotAllowedException() {
		super(ErrorCode.COUPON_STOCK_PREPARATION_NOT_ALLOWED);
	}
}
