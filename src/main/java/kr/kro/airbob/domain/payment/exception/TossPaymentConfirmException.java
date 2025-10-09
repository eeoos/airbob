package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import kr.kro.airbob.domain.payment.exception.code.TossErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentConfirmException extends TossPaymentException {

	public TossPaymentConfirmException(TossErrorCode errorCode) {
		super(errorCode);
	}
}
