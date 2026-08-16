package kr.kro.airbob.cursor.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CursorDecodingException extends BaseException {

	public CursorDecodingException(Throwable cause) {
		super(cause, ErrorCode.CURSOR_DECODING_ERROR);
	}
}
