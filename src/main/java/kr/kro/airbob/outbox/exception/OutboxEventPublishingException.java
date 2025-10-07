package kr.kro.airbob.outbox.exception;

public class OutboxEventPublishingException extends RuntimeException{

	private static final String ERROR_MESSAGE = "Outbox 이벤트 저장에 실패했습니다. 트랜잭션이 롤백됩니다.";

	public OutboxEventPublishingException(Throwable cause) {
		super(ERROR_MESSAGE, cause);
	}
}
