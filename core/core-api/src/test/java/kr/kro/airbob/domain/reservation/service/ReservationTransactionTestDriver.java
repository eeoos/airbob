package kr.kro.airbob.domain.reservation.service;

import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.entity.Reservation;

public final class ReservationTransactionTestDriver {

	private ReservationTransactionTestDriver() {
	}

	public static Reservation createPendingReservation(
		ReservationTransactionService service,
		ReservationCreateCommand command,
		Long memberId,
		String reason
	) {
		return service.createPendingReservationInTx(command, memberId, reason);
	}
}
