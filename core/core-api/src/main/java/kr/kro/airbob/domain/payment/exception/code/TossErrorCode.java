package kr.kro.airbob.domain.payment.exception.code;

import org.springframework.http.HttpStatus;

public interface TossErrorCode {
	HttpStatus getStatusCode();
	String getErrorCode();
	String name();
}
