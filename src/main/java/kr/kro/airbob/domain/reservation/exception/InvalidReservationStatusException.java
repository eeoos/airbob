package kr.kro.airbob.domain.reservation.exception;

public class InvalidReservationStatusException extends RuntimeException{
	public static final String ERROR_MESSAGE = "결제 대기 상태의 예약만 확정할 수 있습니다.";

	public InvalidReservationStatusException() {
		super(ERROR_MESSAGE);
	}

	public InvalidReservationStatusException(String message) {
		super(message);
	}
}
