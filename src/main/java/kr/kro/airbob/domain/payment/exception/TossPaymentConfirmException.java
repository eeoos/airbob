package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentConfirmException extends RuntimeException{

	private final PaymentConfirmErrorCode errorCode;

	public TossPaymentConfirmException(PaymentConfirmErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
