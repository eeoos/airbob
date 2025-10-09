package kr.kro.airbob.domain.payment.exception;

import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import lombok.Getter;

@Getter
public class VirtualAccountIssueException extends TossPaymentException{


	public VirtualAccountIssueException(VirtualAccountIssueErrorCode errorCode) {
		super(errorCode);
	}
}
