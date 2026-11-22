package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentOperationConflictException extends BaseException {

	public PaymentOperationConflictException() {
		super(ErrorCode.PAYMENT_OPERATION_CONFLICT);
	}
}
