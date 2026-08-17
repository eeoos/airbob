package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PaymentOperationConflictException extends BaseException {

	public PaymentOperationConflictException() {
		super(ErrorCode.PAYMENT_OPERATION_CONFLICT);
	}
}
