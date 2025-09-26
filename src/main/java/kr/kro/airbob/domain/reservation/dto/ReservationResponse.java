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

	@Builder
	public record Ready(
		String reservationUid, // toss orderId
		String orderName,
		Integer amount,
		String customerEmail,
		String customerName
	) {
		public static Ready from(Reservation reservation) {
			return Ready.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.orderName(reservation.getAccommodation().getName())
				.amount(reservation.getTotalPrice())
				.customerEmail(reservation.getGuest().getEmail())
				.customerName(reservation.getGuest().getNickname())
				.build();
		}
	}
}
