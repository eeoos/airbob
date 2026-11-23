package kr.kro.airbob.core.domain.payment;

import lombok.Getter;

@Getter
public class TossPaymentException extends RuntimeException{

	private final TossErrorCode errorCode;

	public TossPaymentException(TossErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
