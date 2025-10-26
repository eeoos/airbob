package kr.kro.airbob.common.exception;

import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {
	public static final String COLON = ":";
	private final ErrorCode errorCode;

	public BaseException(String message, ErrorCode errorCode) {
		super(errorCode.getMessage() + COLON + message);
		this.errorCode = errorCode;
	}

	public BaseException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public BaseException(Throwable cause, ErrorCode errorCode) {
		super(cause);
		this.errorCode = errorCode;
	}

}
