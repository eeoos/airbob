package kr.kro.airbob.common.exception;

public class InvalidInputException extends BaseException {
	public InvalidInputException() {
		super(ErrorCode.INVALID_INPUT);
	}
}
