package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationDetailProjection;

public final class ReservationReadTestFixtures {
	private ReservationReadTestFixtures() {
	}

	public static GuestReservationDetailProjection guestDetail(Reservation reservation) {
		var accommodation = reservation.getAccommodation();
		var address = accommodation.getAddress();
		var host = accommodation.getMember();
		return new GuestReservationDetailProjection(
			reservation.getReservationUid(), reservation.getReservationCode(), reservation.getStatus(),
			reservation.getExpiresAt(), reservation.getTotalPrice(), reservation.getCreatedAt(), reservation.getGuestCount(),
			reservation.getCheckInAt(), reservation.getCheckOutAt(), reservation.getTimeZoneId(), reservation.getMessage(),
			accommodation.getId(), accommodation.getName(), accommodation.getThumbnailUrl(), accommodation.getStatus(),
			address == null ? null : address.getCountry(), address == null ? null : address.getState(),
			address == null ? null : address.getCity(), address == null ? null : address.getDistrict(),
			address == null ? null : address.getStreet(), address == null ? null : address.getDetail(),
			address == null ? null : address.getPostalCode(), address == null ? null : address.getLatitude(),
			address == null ? null : address.getLongitude(), host.getId(), host.getNickname(), host.getThumbnailImageUrl(),
			null, null, null, null, null);
	}
}
