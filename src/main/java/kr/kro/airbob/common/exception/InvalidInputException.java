package kr.kro.airbob.common.exception;

public class InvalidInputException extends BaseException {
	public InvalidInputException() {
		super(ErrorCode.INVALID_INPUT);
	}

	public InvalidInputException(String message) {
		super(message, ErrorCode.INVALID_INPUT_VALUE);
	}
}
