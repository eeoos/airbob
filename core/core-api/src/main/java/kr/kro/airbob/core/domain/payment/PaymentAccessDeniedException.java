package kr.kro.airbob.core.domain.payment;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentAccessDeniedException extends BaseException {

	public PaymentAccessDeniedException() {
		super(ErrorCode.PAYMENT_ACCESS_DENIED);
	}
}
