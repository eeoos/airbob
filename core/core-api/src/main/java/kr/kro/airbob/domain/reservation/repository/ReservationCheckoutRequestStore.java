package kr.kro.airbob.domain.reservation.repository;

import java.time.Instant;

import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;

public interface ReservationCheckoutRequestStore {
	ReservationCheckoutRequestClaim lockOrCreate(
		long memberId,
		ReservationCheckoutIdentity identity,
		Instant createdAt
	);

	void complete(long checkoutRequestId, long reservationId, Instant completedAt);
}
