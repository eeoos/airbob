package kr.kro.airbob.domain.reservation.repository;

public record ReservationCheckoutRequestClaim(
	long id,
	String requestFingerprint,
	Long reservationId
) {
}
