package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentInquiryException extends RuntimeException{

	private final PaymentInquiryErrorCode errorCode;

	public TossPaymentInquiryException(PaymentInquiryErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
