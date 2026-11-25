package kr.kro.airbob.core.domain.reservation;

public final class ReservationCheckoutTestDriver {

	private ReservationCheckoutTestDriver() {}

	public static Reservation createPendingReservation(
		ReservationCheckoutService service,
		ReservationCreateCommand command,
		Long memberId,
		String reason) {
		return service.createPendingReservationInTx(command, memberId, reason);
	}
}
