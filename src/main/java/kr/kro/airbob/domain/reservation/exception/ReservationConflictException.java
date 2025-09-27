package kr.kro.airbob.domain.reservation.exception;

public class ReservationConflictException extends RuntimeException {

	public static final String ERROR_MESSAGE = "해당 날짜에 이미 확정된 예약이 존재합니다.";

	public ReservationConflictException() {
		super(ERROR_MESSAGE);
	}

	public ReservationConflictException(String message) {
		super(message);
	}
}
