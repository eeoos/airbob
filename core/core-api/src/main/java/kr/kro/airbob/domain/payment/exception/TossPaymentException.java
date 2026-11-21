package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.TossErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentException extends RuntimeException{

	private final TossErrorCode errorCode;

	public TossPaymentException(TossErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
