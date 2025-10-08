package kr.kro.airbob.cursor.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CursorEncodingException extends BaseException {

	public CursorEncodingException() {
		super(ErrorCode.CURSOR_ENCODING_ERROR);
	}

	public CursorEncodingException(String message) {
		super(message, ErrorCode.CURSOR_ENCODING_ERROR);
	}
}
