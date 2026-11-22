package kr.kro.airbob.core.support.cursor.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class CursorPageSizeException extends BaseException {

	public CursorPageSizeException() {
		super(ErrorCode.CURSOR_PAGE_SIZE_INVALID);
	}
}
