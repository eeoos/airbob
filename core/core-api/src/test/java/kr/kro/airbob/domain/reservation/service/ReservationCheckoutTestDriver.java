package kr.kro.airbob.domain.reservation.service;

import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.entity.Reservation;

public final class ReservationCheckoutTestDriver {

	private ReservationCheckoutTestDriver() {
	}

	public static Reservation createPendingReservation(
		ReservationCheckoutService service,
		ReservationCreateCommand command,
		Long memberId,
		String reason
	) {
		return service.createPendingReservationInTx(command, memberId, reason);
	}
}
