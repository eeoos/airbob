package kr.kro.airbob.core.support.cursor.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CursorEncodingException extends BaseException {

	public CursorEncodingException() {
		super(ErrorCode.CURSOR_ENCODING_ERROR);
	}

	public CursorEncodingException(String message) {
		super(message, ErrorCode.CURSOR_ENCODING_ERROR);
	}
}
