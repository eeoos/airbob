package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentOperationExecutionFenceUnavailableException extends BaseException {

	public PaymentOperationExecutionFenceUnavailableException() {
		super(ErrorCode.PAYMENT_OPERATION_EXECUTION_FENCE_UNAVAILABLE);
	}
}
