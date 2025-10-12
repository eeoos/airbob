package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class TossPaymentResponseParsingException extends BaseException {

	public TossPaymentResponseParsingException(ErrorCode errorCode) {
		super(errorCode);
	}


	public TossPaymentResponseParsingException(Throwable cause, ErrorCode errorCode) {
		super(cause, errorCode);
	}
}
