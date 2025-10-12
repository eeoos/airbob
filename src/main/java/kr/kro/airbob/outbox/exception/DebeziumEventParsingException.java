package kr.kro.airbob.outbox.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class DebeziumEventParsingException extends BaseException {
	public DebeziumEventParsingException(Throwable cause) {
		super(cause, ErrorCode.DEBEZIUM_EVENT_PARSING_ERROR);
	}
}
