package kr.kro.airbob.domain.payment.exception;

public class PaymentNotFoundException extends RuntimeException{

	public static final String ERROR_MESSAGE = "존재하지 않는 결제 정보입니다.";

	public PaymentNotFoundException() {
		super(ERROR_MESSAGE);
	}
}
