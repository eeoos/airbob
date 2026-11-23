package kr.kro.airbob.domain.reservation.idempotency;

public record ReservationCheckoutRequestClaim(
	long id,
	String requestFingerprint,
	Long reservationId
) {
}
