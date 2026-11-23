package kr.kro.airbob.core.domain.payment;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class PaymentOperationNotFoundException extends BaseException {

	public PaymentOperationNotFoundException() {
		super(ErrorCode.PAYMENT_OPERATION_NOT_FOUND);
	}
}
