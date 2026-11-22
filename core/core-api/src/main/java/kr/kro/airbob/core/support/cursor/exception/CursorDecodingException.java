package kr.kro.airbob.core.support.cursor.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CursorDecodingException extends BaseException {

	public CursorDecodingException(Throwable cause) {
		super(cause, ErrorCode.CURSOR_DECODING_ERROR);
	}
}
