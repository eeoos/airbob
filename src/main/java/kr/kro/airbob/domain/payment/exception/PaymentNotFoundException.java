package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PaymentNotFoundException extends BaseException {

	public PaymentNotFoundException() {
		super(ErrorCode.PAYMENT_NOT_FOUND);
	}
}
