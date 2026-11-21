package kr.kro.airbob.messaging.event;

public final class InvalidIntegrationEventException extends RuntimeException {

	public InvalidIntegrationEventException() {
		super("Invalid integration event.");
	}
}
