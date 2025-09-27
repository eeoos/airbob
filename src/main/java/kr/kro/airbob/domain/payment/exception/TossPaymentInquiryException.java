package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentCancelException extends RuntimeException{

	private final PaymentCancelErrorCode errorCode;

	public TossPaymentCancelException(PaymentCancelErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
