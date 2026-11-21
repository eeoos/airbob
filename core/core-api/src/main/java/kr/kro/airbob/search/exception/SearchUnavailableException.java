package kr.kro.airbob.search.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class SearchUnavailableException extends BaseException {

	public SearchUnavailableException(Throwable cause) {
		super(cause, ErrorCode.SEARCH_UNAVAILABLE);
	}
}
