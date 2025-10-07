package kr.kro.airbob.domain.reservation.exception;

public class ReservationStateChangeException extends RuntimeException{

	public ReservationStateChangeException(String message, Throwable cause) {
		super(message, cause);
	}
}
