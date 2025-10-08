package kr.kro.airbob.outbox.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class OutboxEventPublishingException extends BaseException {

	public OutboxEventPublishingException(Throwable cause) {
		super(ErrorCode.OUTBOX_PUBLISH_FAILED);
	}
}
