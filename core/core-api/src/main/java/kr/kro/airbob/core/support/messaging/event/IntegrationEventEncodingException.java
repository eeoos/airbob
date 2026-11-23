package kr.kro.airbob.core.support.messaging.event;

public final class IntegrationEventEncodingException extends RuntimeException {

	public IntegrationEventEncodingException() {
		super("Failed to encode integration event.");
	}
}
