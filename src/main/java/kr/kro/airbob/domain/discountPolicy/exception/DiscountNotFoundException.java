package kr.kro.airbob.domain.discountPolicy.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class DiscountNotFoundException extends BaseException {

    public DiscountNotFoundException() {
        super(ErrorCode.DISCOUNT_NOT_FOUND);
    }
}
