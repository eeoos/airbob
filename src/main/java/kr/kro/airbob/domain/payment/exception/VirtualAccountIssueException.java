package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import lombok.Getter;

@Getter
public class VirtualAccountIssueException extends RuntimeException{

	private final VirtualAccountIssueErrorCode errorCode;

	public VirtualAccountIssueException(VirtualAccountIssueErrorCode errorCode) {
		super(errorCode.getErrorCode());
		this.errorCode = errorCode;
	}
}
