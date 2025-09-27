package kr.kro.airbob.domain.reservation.exception;

public class ReservationNotFoundException extends RuntimeException {

	public static final String ERROR_MESSAGE = "존재하지 않는 예약입니다.";

	public ReservationNotFoundException() {
		super(ERROR_MESSAGE);
	}
}
