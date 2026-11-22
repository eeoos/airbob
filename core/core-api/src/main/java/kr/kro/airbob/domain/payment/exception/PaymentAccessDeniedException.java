package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentAccessDeniedException extends BaseException {

	public PaymentAccessDeniedException() {
		super(ErrorCode.PAYMENT_ACCESS_DENIED);
	}
}
