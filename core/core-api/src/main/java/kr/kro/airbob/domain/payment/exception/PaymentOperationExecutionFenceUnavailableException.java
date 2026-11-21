package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PaymentOperationExecutionFenceUnavailableException extends BaseException {

	public PaymentOperationExecutionFenceUnavailableException() {
		super(ErrorCode.PAYMENT_OPERATION_EXECUTION_FENCE_UNAVAILABLE);
	}
}
