package kr.kro.airbob.domain.reservation.exception;

public class ReservationLockException extends RuntimeException{

	public static final String ERROR_MESSAGE = "해당 날짜에 대한 예약 시도가 많습니다. 잠시 후에 다시 시도해주세요.";

	public ReservationLockException() {
		super(ERROR_MESSAGE);
	}

	public ReservationLockException(String message) {
		super(message);
	}
}
