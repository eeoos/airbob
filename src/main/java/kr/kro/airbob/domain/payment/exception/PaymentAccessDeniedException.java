package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PaymentAccessDeniedException extends BaseException {

	public PaymentAccessDeniedException() {
		super(ErrorCode.PAYMENT_ACCESS_DENIED);
	}
}
