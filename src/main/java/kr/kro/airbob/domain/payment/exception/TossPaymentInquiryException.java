package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import lombok.Getter;

@Getter
public class TossPaymentInquiryException extends TossPaymentException{

	public TossPaymentInquiryException(PaymentInquiryErrorCode errorCode) {
		super(errorCode);
	}
}
