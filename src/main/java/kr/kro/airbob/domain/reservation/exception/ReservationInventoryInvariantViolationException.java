package kr.kro.airbob.domain.reservation.exception;

public class ReservationInventoryInvariantViolationException extends IllegalStateException {

	public ReservationInventoryInvariantViolationException(String message) {
		super(message);
	}
}
