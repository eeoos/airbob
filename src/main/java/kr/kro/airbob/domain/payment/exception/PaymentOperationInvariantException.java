package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class PaymentOperationInvariantException extends BaseException {

	public PaymentOperationInvariantException(String message) {
		super(message, ErrorCode.PAYMENT_OPERATION_CONFLICT);
	}
}
