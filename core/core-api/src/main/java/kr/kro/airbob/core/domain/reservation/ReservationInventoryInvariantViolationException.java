package kr.kro.airbob.core.domain.reservation;

public class ReservationInventoryInvariantViolationException extends IllegalStateException {

	public ReservationInventoryInvariantViolationException(String message) {
		super(message);
	}
}
