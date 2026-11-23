package kr.kro.airbob.core.domain.search;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class SearchUnavailableException extends BaseException {

	public SearchUnavailableException(Throwable cause) {
		super(cause, ErrorCode.SEARCH_UNAVAILABLE);
	}
}
