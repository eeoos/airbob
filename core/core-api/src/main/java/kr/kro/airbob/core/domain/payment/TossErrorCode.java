package kr.kro.airbob.core.domain.payment;

import org.springframework.http.HttpStatus;

public interface TossErrorCode {
	HttpStatus getStatusCode();

	String getErrorCode();

	String name();
}
