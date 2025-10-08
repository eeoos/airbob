package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import kr.kro.airbob.domain.payment.exception.code.TossErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentCancelException extends TossPaymentException {

	public TossPaymentCancelException(TossErrorCode errorCode) {
		super(errorCode);
	}
}
