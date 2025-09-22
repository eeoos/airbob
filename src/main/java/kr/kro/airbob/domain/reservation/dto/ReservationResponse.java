package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationResponse {

	@Builder
	public record Create(
		long reservationId,
		long accommodationId,
		String accommodationName,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		int totalPrice,
		ReservationStatus status
	){
		public static Create from(Reservation reservation) {
			return Create.builder()
				.reservationId(reservation.getId())
				.accommodationId(reservation.getAccommodation().getId())
				.accommodationName(reservation.getAccommodation().getName())
				.checkInDate(reservation.getCheckIn().toLocalDate())
				.checkOutDate(reservation.getCheckOut().toLocalDate())
				.totalPrice(reservation.getTotalPrice())
				.status(reservation.getStatus())
				.build();
		}
	}
}
