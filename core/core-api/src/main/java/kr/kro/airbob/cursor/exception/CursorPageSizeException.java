package kr.kro.airbob.cursor.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class CursorPageSizeException extends BaseException {

	public CursorPageSizeException() {
		super(ErrorCode.CURSOR_PAGE_SIZE_INVALID);
	}
}
