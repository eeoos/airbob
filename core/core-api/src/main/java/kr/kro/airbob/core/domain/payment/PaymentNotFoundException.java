package kr.kro.airbob.core.domain.payment;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentNotFoundException extends BaseException {

	public PaymentNotFoundException() {
		super(ErrorCode.PAYMENT_NOT_FOUND);
	}
}
