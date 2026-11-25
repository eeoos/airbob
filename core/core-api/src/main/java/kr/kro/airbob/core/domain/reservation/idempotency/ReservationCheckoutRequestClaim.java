package kr.kro.airbob.core.domain.reservation.idempotency;

public record ReservationCheckoutRequestClaim(
	long id,
	String requestFingerprint,
	Long reservationId) {
}
